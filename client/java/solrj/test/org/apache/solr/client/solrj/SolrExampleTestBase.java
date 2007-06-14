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

package org.apache.solr.client.solrj;


import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.util.AbstractSolrTestCase;

/**
 * This should include tests against the example solr config
 * 
 * This lets us try various SolrServer implementations with the same tests.
 * 
 * @author ryan
 * @version $Id$
 * @since solr 1.3
 */
abstract public class SolrExampleTestBase extends AbstractSolrTestCase 
{
  @Override public String getSchemaFile()     { return "../../../example/solr/conf/schema.xml";     }
  @Override public String getSolrConfigFile() { return "../../../example/solr/conf/solrconfig.xml"; }
  
  /**
   * Subclasses need to initalize the server impl
   */
  protected abstract SolrServer getSolrServer();
  
  /**
   * query the example
   */
  public void testExampleConfig() throws Exception
  {    
    SolrServer server = getSolrServer();
    
    // Empty the database...
    server.deleteByQuery( "*:*" );// delete everything!
    
    // Now add something...
    SolrInputDocument doc = new SolrInputDocument();
    String docID = "1112211111";
    doc.addField( "id", docID );
    doc.addField( "name", "my name!" );
    
    Assert.assertEquals( null, doc.getFieldValue("foo"));
    Assert.assertTrue(doc.getFieldValue("name") != null );
        
    UpdateResponse upres = server.add( doc ); 
    System.out.println( "ADD:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    upres = server.commit( true, true );
    System.out.println( "COMMIT:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    upres = server.optimize( true, true );
    System.out.println( "OPTIMIZE:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    SolrQuery query = new SolrQuery();
    query.setQuery( "id:"+docID );
    QueryResponse response = server.query( query );
    
    Assert.assertEquals(docID, response.getResults().get(0).getFieldValue("id") );
    
    // Now add a few docs for facet testing...
    List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
    SolrInputDocument doc2 = new SolrInputDocument();
    doc2.addField( "id", "2" );
    doc2.addField( "inStock", true );
    doc2.addField( "price", 2 );
    doc2.addField( "timestamp", new java.util.Date() );
    docs.add(doc2);
    SolrInputDocument doc3 = new SolrInputDocument();
    doc3.addField( "id", "3" );
    doc3.addField( "inStock", false );
    doc3.addField( "price", 3 );
    doc3.addField( "timestamp", new java.util.Date() );
    docs.add(doc3);
    SolrInputDocument doc4 = new SolrInputDocument();
    doc4.addField( "id", "4" );
    doc4.addField( "inStock", true );
    doc4.addField( "price", 4 );
    doc4.addField( "timestamp", new java.util.Date() );
    docs.add(doc4);
    SolrInputDocument doc5 = new SolrInputDocument();
    doc5.addField( "id", "5" );
    doc5.addField( "inStock", false );
    doc5.addField( "price", 5 );
    doc5.addField( "timestamp", new java.util.Date() );
    docs.add(doc5);
    
    upres = server.add( docs ); 
    System.out.println( "ADD:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    upres = server.commit( true, true );
    System.out.println( "COMMIT:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    upres = server.optimize( true, true );
    System.out.println( "OPTIMIZE:"+upres.getResponse() );
    Assert.assertEquals(0, upres.getStatus());
    
    query = new SolrQuery("*:*");
    query.addFacetQuery("price:[* TO 2]");
    query.addFacetQuery("price:[2 TO 4]");
    query.addFacetQuery("price:[5 TO *]");
    query.addFacetField("inStock");
    query.addFacetField("price");
    query.addFacetField("timestamp");
    query.removeFilterQuery("inStock:true");
    
    response = server.query( query );
    Assert.assertEquals(0, response.getStatus());
    Assert.assertEquals(5, response.getResults().getNumFound() );
    Assert.assertEquals(3, response.getFacetQuery().size());    
    Assert.assertEquals(2, response.getFacetField("inStock").getValueCount());
    Assert.assertEquals(4, response.getFacetField("price").getValueCount());
    
    // test a second query, test making a copy of the main query
    SolrQuery query2 = query.getCopy();
    query2.addFilterQuery("inStock:true");
    response = server.query( query2 );
    Assert.assertEquals(1, query2.getFilterQueries().length);
    Assert.assertEquals(0, response.getStatus());
    Assert.assertEquals(2, response.getResults().getNumFound() );
    Assert.assertFalse(query.getFilterQueries() == query2.getFilterQueries());
  }
}
