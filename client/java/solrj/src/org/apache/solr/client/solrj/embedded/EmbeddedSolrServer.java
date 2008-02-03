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

package org.apache.solr.client.solrj.embedded;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.DefaultSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.MultiCore;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.QueryResponseWriter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.servlet.SolrRequestParsers;

/**
 * SolrServer that connects directly to SolrCore
 * 
 * TODO -- this implementation sends the response to XML and then parses it.  
 * It *should* be able to convert the response directly into a named list.
 * 
 * @version $Id$
 * @since solr 1.3
 */
public class EmbeddedSolrServer extends SolrServer
{
  protected ModifiableSolrParams _invariantParams;
  protected ResponseParser _processor;
  
  protected final SolrCore core;
  protected final SolrRequestParsers parser;
  protected final String coreName;  // use MultiCore registry
  
  public EmbeddedSolrServer( SolrCore core )
  {
    this.core = core;
    this.coreName = null;
    this.parser = init();
  }
    
  public EmbeddedSolrServer( String coreName )
  {
    this.core = null;
    this.coreName = coreName;
    SolrCore c = MultiCore.getRegistry().getCore( coreName );
    if( c == null ) {
      throw new RuntimeException( "Unknown core: "+coreName );
    }
    this.parser = init();
  }
  
  private SolrRequestParsers init()
  {
    _processor = new XMLResponseParser();

    _invariantParams = new ModifiableSolrParams();
    _invariantParams.set( CommonParams.WT, _processor.getWriterType() );
    _invariantParams.set( CommonParams.VERSION, "2.2" );
    
    return new SolrRequestParsers( true, Long.MAX_VALUE );
  }

  @Override
  public NamedList<Object> request(SolrRequest request) throws SolrServerException, IOException 
  {
    String path = request.getPath();
    if( path == null || !path.startsWith( "/" ) ) {
      path = "/select";
    }

    // Check for multicore action
    MultiCore multicore = MultiCore.getRegistry();
    SolrCore core = this.core;
    if( core == null ) {
      core = multicore.getCore( coreName );
      if( core == null ) {
        throw new SolrException( SolrException.ErrorCode.SERVER_ERROR, 
            "Unknown core: "+coreName );
      }
    }

    SolrParams params = request.getParams();
    if( params == null ) {
      params = new ModifiableSolrParams();
    }
    if( _invariantParams != null ) {
      params = new DefaultSolrParams( _invariantParams, params );
    }
    
    // Extract the handler from the path or params
    SolrRequestHandler handler = core.getRequestHandler( path );
    if( handler == null ) {
      if( "/select".equals( path ) || "/select/".equalsIgnoreCase( path) ) {
        String qt = params.get( CommonParams.QT );
        handler = core.getRequestHandler( qt );
        if( handler == null ) {
          throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "unknown handler: "+qt);
        }
      }
      // Perhaps the path is to manage the cores
      if( handler == null &&
          coreName != null && 
          path.equals( multicore.getAdminPath() ) && 
          multicore.isEnabled() ) {
        handler = multicore.getMultiCoreHandler();
      }
    }
    if( handler == null ) {
      throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "unknown handler: "+path );
    }
    
    try {
      SolrQueryRequest req = parser.buildRequestFrom( core, params, request.getContentStreams() );
      req.getContext().put( "path", path );
      SolrQueryResponse rsp = new SolrQueryResponse();
      core.execute( handler, req, rsp );
      if( rsp.getException() != null ) {
        throw new SolrServerException( rsp.getException() );
      }
      
      // Now write it out
      QueryResponseWriter responseWriter = core.getQueryResponseWriter(req);
      StringWriter out = new StringWriter();
      responseWriter.write(out, req, rsp);
      // TODO: writers might be able to output binary someday
      
      req.close();
      return _processor.processResponse( new StringReader( out.toString() ) );
    }
    catch( IOException iox ) {
      throw iox;
    }
    catch( Exception ex ) {
      throw new SolrServerException( ex );
    }
  }
}
