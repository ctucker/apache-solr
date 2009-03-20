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

import junit.framework.TestCase;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.impl.LBHttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.util.AbstractSolrTestCase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test for LBHttpSolrServer
 *
 * @version $Id$
 * @since solr 1.4
 */
public class TestLBHttpSolrServer extends TestCase {
  SolrInstance[] solr = new SolrInstance[3];
  HttpClient httpClient = new HttpClient();

  public void setUp() throws Exception {
    for (int i = 0; i < solr.length; i++) {
      solr[i] = new SolrInstance("solr" + i, 43000 + i);
      solr[i].setUp();
      solr[i].startJetty();
      addDocs(solr[i]);
    }
  }

  private void addDocs(SolrInstance solrInstance) throws IOException, SolrServerException {
    List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField("name", solrInstance.name);
      docs.add(doc);
    }
    CommonsHttpSolrServer solrServer = new CommonsHttpSolrServer(solrInstance.getUrl(), httpClient);
    UpdateResponse resp = solrServer.add(docs);
    assertEquals(0, resp.getStatus());
    resp = solrServer.commit();
    assertEquals(0, resp.getStatus());
  }

  @Override
  public void tearDown() throws Exception {
    for (SolrInstance aSolr : solr) {
      aSolr.tearDown();
    }
  }

  public void testSimple() throws Exception {
    String[] s = new String[solr.length];
    for (int i = 0; i < solr.length; i++) {
      s[i] = solr[i].getUrl();
    }
    LBHttpSolrServer lbHttpSolrServer = new LBHttpSolrServer(httpClient, s);
    lbHttpSolrServer.setAliveCheckInterval(1);
    SolrQuery solrQuery = new SolrQuery("*:*");
    Set<String> names = new HashSet<String>();
    QueryResponse resp = null;
    for (String value : s) {
      resp = lbHttpSolrServer.query(solrQuery);
      assertEquals(10, resp.getResults().getNumFound());
      names.add(resp.getResults().get(0).getFieldValue("name").toString());
    }
    assertEquals(3, names.size());

    // Kill a server and test again
    solr[1].jetty.stop();
    solr[1].jetty = null;
    names.clear();
    for (String value : s) {
      resp = lbHttpSolrServer.query(solrQuery);
      assertEquals(10, resp.getResults().getNumFound());
      names.add(resp.getResults().get(0).getFieldValue("name").toString());
    }
    assertEquals(2, names.size());
    assertFalse(names.contains("solr1"));

    // Start the killed server once again
    solr[1].startJetty();
    // Wait for the alive check to complete
    Thread.sleep(1200);
    names.clear();
    for (String value : s) {
      resp = lbHttpSolrServer.query(solrQuery);
      assertEquals(10, resp.getResults().getNumFound());
      names.add(resp.getResults().get(0).getFieldValue("name").toString());
    }
    assertEquals(3, names.size());
  }


  private class SolrInstance extends AbstractSolrTestCase {

    String name;
    File homeDir;
    File confDir;
    int port;
    JettySolrRunner jetty;

    public SolrInstance(String name, int port) {
      this.name = name;
      this.port = port;
    }

    public String getHomeDir() {
      return homeDir.toString();
    }

    public String getUrl() {
      return "http://localhost:" + port + "/solr";
    }

    @Override
    public String getSchemaFile() {
      return "." + File.separator + "solr" + File.separator + "conf" + File.separator + "schema-replication1.xml";
    }

    public String getConfDir() {
      return confDir.toString();
    }

    public String getDataDir() {
      return dataDir.toString();
    }

    @Override
    public String getSolrConfigFile() {
      String fname = "";
      fname = "." + File.separator + "solr" + File.separator + "conf" + File.separator + "solrconfig-slave1.xml";
      return fname;
    }

    public void setUp() throws Exception {
      String home = System.getProperty("java.io.tmpdir")
              + File.separator
              + getClass().getName() + "-" + System.currentTimeMillis();


      homeDir = new File(home, name);
      dataDir = new File(homeDir, "data");
      confDir = new File(homeDir, "conf");

      homeDir.mkdirs();
      dataDir.mkdirs();
      confDir.mkdirs();

      File f = new File(confDir, "solrconfig.xml");
      FileUtils.copyFile(new File(getSolrConfigFile()), f);
      f = new File(confDir, "schema.xml");
      FileUtils.copyFile(new File(getSchemaFile()), f);

    }

    public void tearDown() throws Exception {
      try {
        jetty.stop();
      } catch (Exception e) {
      }
      super.tearDown();
      AbstractSolrTestCase.recurseDelete(homeDir);
    }

    public void startJetty() throws Exception {
      jetty = new JettySolrRunner("/solr", port);
      System.setProperty("solr.solr.home", getHomeDir());
      System.setProperty("solr.data.dir", getDataDir());
      jetty.start();
//      System.out.println("waiting.........");
//      Thread.sleep(5000);
    }
  }
}
