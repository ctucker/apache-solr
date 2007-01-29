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

package org.apache.solr.servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.xml.xpath.XPathConstants;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.solr.core.Config;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrException;
import org.apache.solr.request.ContentStream;
import org.apache.solr.request.MultiMapSolrParams;
import org.apache.solr.request.ServletSolrParams;
import org.apache.solr.request.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class SolrRequestParsers 
{
  final Logger log = Logger.getLogger(SolrRequestParsers.class.getName());
  
  // Should these constants be in a more public place?
  public static final String MULTIPART = "multipart";
  public static final String RAW = "raw";
  public static final String SIMPLE = "simple";
  public static final String STANDARD = "standard";
  
  private HashMap<String, SolrRequestParser> parsers;
  private SolrCore core;
  private boolean enableRemoteStreams = false;
  private StandardRequestParser standard;
  
  public SolrRequestParsers( SolrCore core, Config config )
  {
    this.core = core;
    
    long uploadLimitKB = 2000; // 2MB default
    NodeList nodes = (NodeList)config.evaluate("requestParsers", XPathConstants.NODESET);
      if( nodes!=null && nodes.getLength()>0 ) {
          // only look at the first node.  
        NamedNodeMap attrs = nodes.item(0).getAttributes();
        Node node = attrs.getNamedItem( "enableRemoteStreaming" );
        if( node != null ) {
          enableRemoteStreams = Boolean.parseBoolean( node.getTextContent() );
        }
        node = attrs.getNamedItem( "multipartUploadLimitInKB" );
        if( node != null ) {
          uploadLimitKB = Long.parseLong( node.getTextContent() );
        }
      }
    
    MultipartRequestParser multi = new MultipartRequestParser( uploadLimitKB );
    RawRequestParser raw = new RawRequestParser();
    standard = new StandardRequestParser( multi, raw );
    
    // I don't see a need to have this publically configured just yet
    // adding it is trivial
    parsers = new HashMap<String, SolrRequestParser>();
    parsers.put( MULTIPART, multi );
    parsers.put( RAW, raw );
    parsers.put( SIMPLE, new SimpleRequestParser() );
    parsers.put( STANDARD, standard );
    parsers.put( "", standard );
  }
  
  public SolrQueryRequest parse( String path, HttpServletRequest req ) throws Exception
  {
    SolrRequestParser parser = standard;
    
    // TODO -- in the future, we could pick a different parser based on the request
    
    // Pick the parer from the request...
    ArrayList<ContentStream> streams = new ArrayList<ContentStream>(1);
    SolrParams params = parser.parseParamsAndFillStreams( req, streams );
    SolrQueryRequest sreq = buildRequestFrom( params, streams );

    // If there is some path left over, add it to the context
    int idx = req.getServletPath().indexOf( ':' );
    if( idx > 0 ) {
      sreq.getContext().put( "path", req.getServletPath().substring( idx+1 ) );
    }
    return sreq;
  }
  
  SolrQueryRequest buildRequestFrom( SolrParams params, List<ContentStream> streams ) throws Exception
  {
    // Handle anything with a remoteURL
    String[] strs = params.getParams( SolrParams.STREAM_URL );
    if( strs != null ) {
      if( !enableRemoteStreams ) {
        throw new SolrException( 400, "Remote Streaming is disabled." );
      }
      for( final String url : strs ) {
        final URLConnection conn = new URL(url).openConnection();
        streams.add( new ContentStream() {
          public String getContentType() { return conn.getContentType(); } 
          public String getName() { return url; }
          public Long getSize() { return new Long( conn.getContentLength() ); }
          public String getSourceInfo() {
            return SolrParams.STREAM_URL;
          }
          public InputStream getStream() throws IOException {
            return conn.getInputStream();
          }
        });
      }
    }
    
    // Check for streams in the request parameters
    strs = params.getParams( SolrParams.STREAM_BODY );
    if( strs != null ) {
      for( final String body : strs ) {
        streams.add( new ContentStream() {
          public String getContentType() { return null; } // Is there anything meaningful?
          public String getName() { return null; }
          public Long getSize() { return null; }
          public String getSourceInfo() {
            return SolrParams.STREAM_BODY;
          }
          public InputStream getStream() throws IOException {
            return new ByteArrayInputStream( body.getBytes() );
          }
        });
      }
    }
    
    SolrQueryRequestBase q = new SolrQueryRequestBase( core, params ) { };
    if( streams != null && streams.size() > 0 ) {
      q.setContentStreams( streams );
    }
    
    return q;
  }
  

  /**
   * Given a standard query string map it into solr params
   */
    public static MultiMapSolrParams parseQueryString(String queryString) 
  {
    Map<String,String[]> map = new HashMap<String, String[]>();
    if( queryString != null && queryString.length() > 0 ) {
      for( String kv : queryString.split( "&" ) ) {
        int idx = kv.indexOf( '=' );
        if( idx > 0 ) {
          String name = URLDecoder.decode( kv.substring( 0, idx ));
          String value = URLDecoder.decode( kv.substring( idx+1 ));
          MultiMapSolrParams.addParam( name, value, map );
        }
        else {
          String name = URLDecoder.decode( kv );
          MultiMapSolrParams.addParam( name, "", map );
        }
      }
    }
    return new MultiMapSolrParams( map );
  }
}

//-----------------------------------------------------------------
//-----------------------------------------------------------------

// I guess we don't really even need the interface, but i'll keep it here just for kicks
interface SolrRequestParser 
{
  public SolrParams parseParamsAndFillStreams(
    final HttpServletRequest req, ArrayList<ContentStream> streams ) throws Exception;
}


//-----------------------------------------------------------------
//-----------------------------------------------------------------

/**
 * The simple parser just uses the params directly
 */
class SimpleRequestParser implements SolrRequestParser
{
  public SolrParams parseParamsAndFillStreams( 
      final HttpServletRequest req, ArrayList<ContentStream> streams ) throws Exception
  {
    return new ServletSolrParams(req);
  }
}


/**
 * The simple parser just uses the params directly
 */
class RawRequestParser implements SolrRequestParser
{
  public SolrParams parseParamsAndFillStreams( 
      final HttpServletRequest req, ArrayList<ContentStream> streams ) throws Exception
  {
    streams.add( new ContentStream() {
      public String getContentType() {
        return req.getContentType();
      }
      public String getName() {
        return null; // Is there any meaningfull name?
      }
      public String getSourceInfo() {
        return null; // Is there any meaningfull name?
      }
      public Long getSize() { 
        String v = req.getHeader( "Content-Length" );
        if( v != null ) {
          return Long.valueOf( v );
        }
        return null; 
      }
      public InputStream getStream() throws IOException {
        return req.getInputStream();
      }
    });
    return SolrRequestParsers.parseQueryString( req.getQueryString() );
  }
}



/**
 * Extract Multipart streams
 */
class MultipartRequestParser implements SolrRequestParser
{
  private long uploadLimitKB;
  
  public MultipartRequestParser( long limit )
  {
    uploadLimitKB = limit;
  }
  
  public SolrParams parseParamsAndFillStreams( 
      final HttpServletRequest req, ArrayList<ContentStream> streams ) throws Exception
  {
    if( !ServletFileUpload.isMultipartContent(req) ) {
      throw new SolrException( 400, "Not multipart content! "+req.getContentType() );
    }
    
    MultiMapSolrParams params = SolrRequestParsers.parseQueryString( req.getQueryString() );
    
    // Create a factory for disk-based file items
    DiskFileItemFactory factory = new DiskFileItemFactory();

    // Set factory constraints
    // TODO - configure factory.setSizeThreshold(yourMaxMemorySize);
    // TODO - configure factory.setRepository(yourTempDirectory);

    // Create a new file upload handler
    ServletFileUpload upload = new ServletFileUpload(factory);
    upload.setSizeMax( uploadLimitKB*1024 );

    // Parse the request
    List items = upload.parseRequest(req);
    Iterator iter = items.iterator();
    while (iter.hasNext()) {
        FileItem item = (FileItem) iter.next();

        // If its a form field, put it in our parameter map
        if (item.isFormField()) {
          MultiMapSolrParams.addParam( 
            item.getFieldName(), 
            item.getString(), params.getMap() );
        }
        // Only add it if it actually has something...
        else if( item.getSize() > 0 ) { 
          streams.add( new FileItemContentStream( item ) );
        }
    }
    return params;
  }
  
  /**
   * Wrap a FileItem as a ContentStream
   */
  private static class FileItemContentStream implements ContentStream
  {
    FileItem item;
    
    public FileItemContentStream( FileItem f )
    {
      item = f;
    }
    
    public String getContentType() {
      return item.getContentType();
    }
    
    public String getName() {
      return item.getName();
    }
    
    public InputStream getStream() throws IOException {
      return item.getInputStream();
    }

    public String getSourceInfo() {
      return item.getFieldName();
    }
    
    public Long getSize()
    {
      return item.getSize();
    }
  }
}


/**
 * The default Logic
 */
class StandardRequestParser implements SolrRequestParser
{
  MultipartRequestParser multipart;
  RawRequestParser raw;
  
  StandardRequestParser( MultipartRequestParser multi, RawRequestParser raw ) 
  {
    this.multipart = multi;
    this.raw = raw;
  }
  
  public SolrParams parseParamsAndFillStreams( 
      final HttpServletRequest req, ArrayList<ContentStream> streams ) throws Exception
  {
    String method = req.getMethod().toUpperCase();
    if( "GET".equals( method ) ) {
      return new ServletSolrParams(req);
    }
    if( "POST".equals( method ) ) {
      String contentType = req.getContentType();
      if( contentType != null ) {
        if( "application/x-www-form-urlencoded".equals( contentType.toLowerCase() ) ) {
          return new ServletSolrParams(req); // just get the params from parameterMap
        }
        if( ServletFileUpload.isMultipartContent(req) ) {
          return multipart.parseParamsAndFillStreams(req, streams);
        }
      }
      return raw.parseParamsAndFillStreams(req, streams);
    }
    throw new SolrException( 400, "Unsuported method: "+method );
  }
}



