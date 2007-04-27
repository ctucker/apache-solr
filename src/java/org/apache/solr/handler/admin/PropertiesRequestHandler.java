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

package org.apache.solr.handler.admin;

import java.io.IOException;

import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.util.SimpleOrderedMap;

/**
 * @author ryan
 * @version $Id$
 * @since solr 1.2
 */
public class PropertiesRequestHandler extends RequestHandlerBase
{
  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException 
  {
    SimpleOrderedMap<String> props = new SimpleOrderedMap<String>();
    String name = req.getParams().get( "name" );
    if( name != null ) {
      props.add( name, System.getProperty(name) );
    }
    else {
      java.util.Enumeration e = System.getProperties().propertyNames();
      while(e.hasMoreElements()) {
        String prop = (String)e.nextElement();
        props.add( prop, System.getProperty(prop) );
      }
    }
    rsp.add( "system.properties", props );
  }

  //////////////////////// SolrInfoMBeans methods //////////////////////

  @Override
  public String getDescription() {
    return "Get System Properties";
  }

  @Override
  public String getVersion() {
      return "$Revision$";
  }

  @Override
  public String getSourceId() {
    return "$Id$";
  }

  @Override
  public String getSource() {
    return "$URL$";
  }
}
