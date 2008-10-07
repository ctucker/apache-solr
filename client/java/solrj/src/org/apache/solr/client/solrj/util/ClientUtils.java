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

package org.apache.solr.client.solrj.util;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.util.DateParseException;
import org.apache.commons.httpclient.util.DateUtil;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.XML;


/**
 * TODO? should this go in common?
 * 
 * @version $Id$
 * @since solr 1.3
 */
public class ClientUtils 
{
  // Standard Content types
  public static final String TEXT_XML = "text/xml; charset=utf-8";  
  
  /**
   * Take a string and make it an iterable ContentStream
   */
  public static Collection<ContentStream> toContentStreams( final String str, final String contentType )
  {
    if( str == null )
      return null;
    
    ArrayList<ContentStream> streams = new ArrayList<ContentStream>( 1 );
    ContentStreamBase ccc = new ContentStreamBase.StringStream( str );
    ccc.setContentType( contentType );
    streams.add( ccc );
    return streams;
  }
  
  /**
   * @param d SolrDocument to convert
   * @return a SolrInputDocument with the same fields and values as the 
   *   SolrDocument.  All boosts are 1.0f
   */
  public static SolrInputDocument toSolrInputDocument( SolrDocument d )
  {
    SolrInputDocument doc = new SolrInputDocument();
    for( String name : d.getFieldNames() ) {
      doc.addField( name, d.getFieldValue(name), 1.0f );
    }
    return doc;
  }

  /**
   * @param d SolrInputDocument to convert
   * @return a SolrDocument with the same fields and values as the SolrInputDocument
   */
  public static SolrDocument toSolrDocument( SolrInputDocument d )
  {
    SolrDocument doc = new SolrDocument();
    for( SolrInputField field : d ) {
      doc.setField( field.getName(), field.getValue() );
    }
    return doc;
  }
  
  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  
  public static void writeXML( SolrInputDocument doc, Writer writer ) throws IOException
  {
    writer.write("<doc boost=\""+doc.getDocumentBoost()+"\">");
   
    for( SolrInputField field : doc ) {
      float boost = field.getBoost();
      String name = field.getName();
      for( Object v : field ) {
        if (v instanceof Date) {
          v = fmtThreadLocal.get().format( (Date)v );
        }
        if( boost != 1.0f ) {
          XML.writeXML(writer, "field", v.toString(), "name", name, "boost", boost ); 
        }
        else {
          XML.writeXML(writer, "field", v.toString(), "name", name ); 
        }
        
        // only write the boost for the first multi-valued field
        // otherwise, the used boost is the product of all the boost values
        boost = 1.0f; 
      }
    }
    writer.write("</doc>");
  }
  

  public static String toXML( SolrInputDocument doc ) 
  {
    StringWriter str = new StringWriter();
    try {
      writeXML( doc, str );
    }
    catch( Exception ex ){}
    return str.toString();
  }
  
  //---------------------------------------------------------------------------------------

  public static final Collection<String> fmts = new ArrayList<String>();
  static {
    fmts.add( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
    fmts.add( "yyyy-MM-dd'T'HH:mm:ss" );
    fmts.add( "yyyy-MM-dd" );
  }
  
  /**
   * Returns a formatter that can be use by the current thread if needed to
   * convert Date objects to the Internal representation.
   * @throws ParseException 
   * @throws DateParseException 
   */
  public static Date parseDate( String d ) throws ParseException, DateParseException 
  {
    // 2007-04-26T08:05:04Z
    if( d.endsWith( "Z" ) && d.length() > 20 ) {
      return getThreadLocalDateFormat().parse( d );
    }
    return DateUtil.parseDate( d, fmts ); 
  }
  
  /**
   * Returns a formatter that can be use by the current thread if needed to
   * convert Date objects to the Internal representation.
   */
  public static DateFormat getThreadLocalDateFormat() {
  
    return fmtThreadLocal.get();
  }

  public static TimeZone UTC = TimeZone.getTimeZone("UTC");
  private static ThreadLocalDateFormat fmtThreadLocal = new ThreadLocalDateFormat();
  
  private static class ThreadLocalDateFormat extends ThreadLocal<DateFormat> {
    DateFormat proto;
    public ThreadLocalDateFormat() {
      super();
                                    //2007-04-26T08:05:04Z
      SimpleDateFormat tmp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
      tmp.setTimeZone(UTC);
      proto = tmp;
    }
    
    @Override
    protected DateFormat initialValue() {
      return (DateFormat) proto.clone();
    }
  }
  
  private static final Pattern escapePattern = Pattern.compile( "(\\W)" );
  
  /**
   * Non-word characters are escaped by a preceding <code>\</code>.
   */
  public static String escapeQueryChars( String input ) 
  {
    Matcher matcher = escapePattern.matcher( input );
    return matcher.replaceAll( "\\\\$1" );
  }

  /**
   * See: http://lucene.apache.org/java/docs/queryparsersyntax.html#Escaping Special Characters
   */
  public static String escape(String s) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      // These characters are part of the query syntax and must be escaped
      if (c == '\\' || c == '+' || c == '-' || c == '!' || c == '(' || c == ')' || c == ':'
        || c == '^' || c == '[' || c == ']' || c == '\"' || c == '{' || c == '}' || c == '~'
        || c == '*' || c == '?' || c == '|' || c == '&') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }
  
  public static String toQueryString( SolrParams params, boolean xml ) {
    StringBuilder sb = new StringBuilder(128);
    try {
      String amp = xml ? "&amp;" : "&";
      boolean first=true;
      Iterator<String> names = params.getParameterNamesIterator();
      while( names.hasNext() ) {
        String key = names.next();
        String[] valarr = params.getParams( key );
        if( valarr == null ) {
          sb.append( first?"?":amp );
          sb.append(key);
          first=false;
        }
        else {
          for (String val : valarr) {
            sb.append( first? "?":amp );
            sb.append(key);
            if( val != null ) {
              sb.append('=');
              sb.append( URLEncoder.encode( val, "UTF-8" ) );
            }
            first=false;
          }
        }
      }
    }
    catch (IOException e) {throw new RuntimeException(e);}  // can't happen
    return sb.toString();
  }
}
