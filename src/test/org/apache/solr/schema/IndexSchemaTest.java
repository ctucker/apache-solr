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

package org.apache.solr.schema;

import java.util.HashMap;
import java.util.Map;

import org.apache.solr.core.SolrCore;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.MapSolrParams;
import org.apache.solr.request.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.util.AbstractSolrTestCase;


public class IndexSchemaTest extends AbstractSolrTestCase {

  @Override public String getSchemaFile() { return "schema.xml"; }
  @Override public String getSolrConfigFile() { return "solrconfig.xml"; }

  @Override 
  public void setUp() throws Exception {
    super.setUp();
  }
  
  @Override 
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * This test assumes the schema includes:
   * <dynamicField name="dynamic_*" type="string" indexed="true" stored="true"/>
   * <dynamicField name="*_dynamic" type="string" indexed="true" stored="true"/>
   */
  public void testDynamicCopy() 
  {
    assertU(adoc("id", "10", "title", "test", "aaa_dynamic", "aaa"));
    assertU(commit());
    
    Map<String,String> args = new HashMap<String, String>();
    args.put( SolrParams.Q, "title:test" );
    args.put( "indent", "true" );
    SolrQueryRequest req = new LocalSolrQueryRequest( SolrCore.getSolrCore(), new MapSolrParams( args) );
    
    assertQ("Make sure they got in", req
            ,"//*[@numFound='1']"
            ,"//result/doc[1]/int[@name='id'][.='10']"
            );
    
    args = new HashMap<String, String>();
    args.put( SolrParams.Q, "aaa_dynamic:aaa" );
    args.put( "indent", "true" );
    req = new LocalSolrQueryRequest( SolrCore.getSolrCore(), new MapSolrParams( args) );
    assertQ("dynamic source", req
            ,"//*[@numFound='1']"
            ,"//result/doc[1]/int[@name='id'][.='10']"
            );

    args = new HashMap<String, String>();
    args.put( SolrParams.Q, "dynamic_aaa:aaa" );
    args.put( "indent", "true" );
    req = new LocalSolrQueryRequest( SolrCore.getSolrCore(), new MapSolrParams( args) );
    assertQ("dynamic destination", req
            ,"//*[@numFound='1']"
            ,"//result/doc[1]/int[@name='id'][.='10']"
            );
  }
}
