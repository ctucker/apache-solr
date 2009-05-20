package org.apache.solr.handler.clustering.carrot2;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.SetBasedFieldSelector;
import org.apache.lucene.search.Query;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.clustering.SearchClusteringEngine;
import org.apache.solr.highlight.SolrHighlighter;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.*;
import org.apache.solr.util.RefCounted;
import org.carrot2.core.*;
import org.carrot2.core.attribute.AttributeNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * Search results clustering engine based on Carrot2 clustering algorithms.
 *
 * Output from this class is subject to change.
 * 
 * @link http://project.carrot2.org
 */
@SuppressWarnings("unchecked")
public class CarrotClusteringEngine extends SearchClusteringEngine {
	private transient static Logger log = LoggerFactory
			.getLogger(CarrotClusteringEngine.class);

	/** Carrot2 controller that manages instances of clustering algorithms */
	private CachingController controller = new CachingController();
	private Class<? extends IClusteringAlgorithm> clusteringAlgorithmClass;
	
	private SolrCore core;
	private String idFieldName;

	public NamedList cluster(Query query, DocList docList, SolrParams solrParams) {
		try {
			// Prepare attributes for Carrot2 clustering call
			Map<String, Object> attributes = new HashMap<String, Object>();
			List<Document> documents = getDocuments(docList, core, query, solrParams);
			attributes.put(AttributeNames.DOCUMENTS, documents);
			attributes.put(AttributeNames.QUERY, query.toString());
			
			// Pass extra overriding attributes from the request, if any
			extractCarrotAttributes(solrParams, attributes);

			// Perform clustering and convert to named list
			return clustersToNamedList(controller.process(attributes,
					clusteringAlgorithmClass).getClusters(), solrParams);
		} catch (Exception e) {
			log.error("Carrot2 clustering failed", e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public String init(NamedList config, final SolrCore core) {
		String result = super.init(config, core);
		SolrParams initParams = SolrParams.toSolrParams(config);
		
		// Initialize Carrot2 controller. Pass initialization attributes, if any. 
		HashMap<String, Object> initAttributes = new HashMap<String, Object>();
		extractCarrotAttributes(initParams, initAttributes);
		this.controller.init(initAttributes);
		
		this.core = core;
		this.idFieldName = core.getSchema().getUniqueKeyField().getName();

		// Make sure the requested Carrot2 clustering algorithm class is available 
		String carrotAlgorithmClassName = initParams.get(CarrotParams.ALGORITHM);
		try {
			Class<?> algorithmClass = Thread.currentThread().getContextClassLoader()
					.loadClass(carrotAlgorithmClassName);
			if (!IClusteringAlgorithm.class.isAssignableFrom(algorithmClass)) {
				throw new IllegalArgumentException("Class provided as "
						+ CarrotParams.ALGORITHM + " must implement "
						+ IClusteringAlgorithm.class.getName());
			}
			this.clusteringAlgorithmClass = (Class<? extends IClusteringAlgorithm>) algorithmClass;
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(
					"Failed to load Carrot clustering algorithm class", e);
		}

		return result;
	}

	/**
	 * Prepares Carrot2 documents for clustering.
	 */
	private List<Document> getDocuments(DocList docList, SolrCore core,
			Query query, SolrParams solrParams) {
		SolrHighlighter highligher = null;

		// Names of fields to deliver content for clustering
		String urlField = solrParams.get(CarrotParams.URL_FIELD_NAME, "url");
		String titleField = solrParams.get(CarrotParams.TITLE_FIELD_NAME, "title");
		String snippetField = solrParams.get(CarrotParams.SNIPPET_FIELD_NAME,
				titleField);
		if (StringUtils.isBlank(snippetField)) {
			throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, CarrotParams.SNIPPET_FIELD_NAME
					+ " must not be blank.");
		}
		Set<String> fieldsToLoad = Sets.newHashSet(urlField, titleField,
				snippetField, idFieldName);

		// Get the documents
		DocIterator docsIter = docList.iterator();
		boolean produceSummary = solrParams.getBool(CarrotParams.PRODUCE_SUMMARY,
				false);

		SolrQueryRequest req = null;
		String[] snippetFieldAry = null;
		if (produceSummary == true) {
			highligher = core.getHighlighter();
			Map args = new HashMap();
			snippetFieldAry = new String[] { snippetField };
			args.put(HighlightParams.FIELDS, snippetFieldAry);
			args.put(HighlightParams.HIGHLIGHT, "true");
			req = new LocalSolrQueryRequest(core, query.toString(), "", 0, 1, args);
		}

		RefCounted<SolrIndexSearcher> refCounter = core.getSearcher();
		SolrIndexSearcher searcher = refCounter.get();
		List<Document> result = new ArrayList<Document>(docList.size());
		try {
			FieldSelector fieldSelector = new SetBasedFieldSelector(fieldsToLoad,
					Collections.emptySet());
			float[] scores = { 1.0f };
			int[] docsHolder = new int[1];
			Query theQuery = query;

			while (docsIter.hasNext()) {
				Integer id = docsIter.next();
				org.apache.lucene.document.Document doc = searcher.doc(id,
						fieldSelector);
				String snippet = getValue(doc, snippetField);
				if (produceSummary == true) {
					docsHolder[0] = id.intValue();
					DocList docAsList = new DocSlice(0, 1, docsHolder, scores, 1, 1.0f);
					highligher.doHighlighting(docAsList, theQuery, req, snippetFieldAry);
				}
				Document carrotDocument = new Document(getValue(doc, titleField),
						snippet, doc.get(urlField));
				carrotDocument.addField("solrId", doc.get(idFieldName));
				result.add(carrotDocument);
			}
		} catch (IOException e) {
			log.error("IOException", e);
		} finally {
			refCounter.decref();
		}
		return result;
	}

	protected String getValue(org.apache.lucene.document.Document doc,
			String field) {
		StringBuilder result = new StringBuilder();
		String[] vals = doc.getValues(field);
		for (int i = 0; i < vals.length; i++) {
			// Join multiple values with a period so that Carrot2 does not pick up
			// phrases that cross field value boundaries (in most cases it would
			// create useless phrases).
			result.append(vals[i]).append(" . ");
		}
		return result.toString().trim();
	}

	private NamedList clustersToNamedList(List<Cluster> carrotClusters,
			SolrParams solrParams) {
		NamedList result = new NamedList();
		clustersToNamedList(carrotClusters, result, solrParams.getBool(
				CarrotParams.OUTPUT_SUB_CLUSTERS, false), solrParams.getInt(
				CarrotParams.NUM_DESCRIPTIONS, Integer.MAX_VALUE));
		return result;
	}

	private void clustersToNamedList(List<Cluster> outputClusters,
			NamedList parent, boolean outputSubClusters, int maxLabels) {
		for (Cluster outCluster : outputClusters) {
			NamedList cluster = new NamedList();
			parent.add("cluster", cluster);

			List<String> labels = outCluster.getPhrases();
			NamedList labelsNL = new NamedList();
			cluster.add("labels", labelsNL);
			int labelsAdded = 0;
			for (String label : labels) {
				if (++labelsAdded > maxLabels) {
					break;
				}
				labelsNL.add("label", label);
			}

			List<Document> docs = outCluster.getDocuments();
			NamedList docsNL = new NamedList();
			cluster.add("docs", docsNL);
			for (Document doc : docs) {
				docsNL.add("doc", doc.getField("solrId"));
			}

			if (outputSubClusters) {
				NamedList subclustersNL = new NamedList();
				cluster.add("clusters", subclustersNL);
				clustersToNamedList(outCluster.getSubclusters(), subclustersNL,
						outputSubClusters, maxLabels);
			}
		}
	}

	/**
	 * Extracts parameters that can possibly match some attributes of Carrot2 algorithms.
	 */
	private void extractCarrotAttributes(SolrParams solrParams,
			Map<String, Object> attributes) {
		// Extract all non-predefined parameters. This way, we'll be able to set all 
		// parameters of Carrot2 algorithms without defining their names as constants.
		for (Iterator<String> paramNames = solrParams.getParameterNamesIterator(); paramNames
				.hasNext();) {
			String paramName = paramNames.next();
			if (!CarrotParams.CARROT_PARAM_NAMES.contains(paramName)) {
				attributes.put(paramName, solrParams.get(paramName));
			}
		}
	}
}
