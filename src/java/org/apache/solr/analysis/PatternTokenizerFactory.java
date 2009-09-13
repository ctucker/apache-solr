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

package org.apache.solr.analysis;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharStream;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.solr.common.SolrException;


/**
 * This tokenizer uses regex pattern matching to construct distinct tokens
 * for the input stream.  It takes two arguments:  "pattern" and "group".
 * <p/>
 * <ul>
 * <li>"pattern" is the regular expression.</li>
 * <li>"group" says which group to extract into tokens.</li>
 *  </ul>
 * <p>
 * group=-1 (the default) is equivalent to "split".  In this case, the tokens will
 * be equivalent to the output from:
 *
 * http://java.sun.com/j2se/1.4.2/docs/api/java/lang/String.html#split(java.lang.String)
 * </p>
 * <p>
 * Using group >= 0 selects the matching group as the token.  For example, if you have:<br/>
 * <pre>
 *  pattern = \'([^\']+)\'
 *  group = 0
 *  input = aaa 'bbb' 'ccc'
 *</pre>
 * the output will be two tokens: 'bbb' and 'ccc' (including the ' marks).  With the same input
 * but using group=1, the output would be: bbb and ccc (no ' marks)
 * </p>
 *
 * @since solr1.2
 * @version $Id:$
 */
public class PatternTokenizerFactory extends BaseTokenizerFactory 
{
  public static final String PATTERN = "pattern";
  public static final String GROUP = "group";
 
  protected Map<String,String> args;
  protected Pattern pattern;
  protected int group;
  
  /**
   * Require a configured pattern
   */
  @Override
  public void init(Map<String,String> args) 
  {
    this.args = args;
    String regex = args.get( PATTERN );
    if( regex == null ) {
      throw new SolrException( SolrException.ErrorCode.SERVER_ERROR, "missing required argument: "+PATTERN );
    }
    int flags = 0; // TODO? -- read flags from config CASE_INSENSITIVE, etc
    pattern = Pattern.compile( regex, flags );
    
    group = -1;  // use 'split'
    String g = args.get( GROUP );
    if( g != null ) {
      try {
        group = Integer.parseInt( g );
      }
      catch( Exception ex ) {
        throw new SolrException( SolrException.ErrorCode.SERVER_ERROR, "invalid group argument: "+g );
      }
    }
  }
  
  /**
   * Split the input using configured pattern
   */
  public Tokenizer create(final Reader in) {
    try {
      return new Tokenizer(in) {
        {init();}

        List<Token> tokens;
        Iterator<Token> iter;

        void init() throws IOException {
          // Read the input into a single string
          String str = IOUtils.toString( input );

          Matcher matcher = pattern.matcher( str );
          tokens = (group < 0 )
                  ? split( matcher, str, input )
                  : group( matcher, str, group, input );
          iter = tokens.iterator();
        }

//        @Override
//        public boolean incrementToken() throws IOException {
//          return super.incrementToken();
//        }

        @Override
        public void end() throws IOException {
          super.end();
        }

//        @Override
//        public Token next(Token reusableToken) throws IOException {
//          return super.next(reusableToken);
//        }

        @Override
        public void reset(Reader input) throws IOException {
          super.reset(input);
          init();
        }

        @Override
        public Token next() throws IOException {
          if( iter.hasNext() ) {
            return iter.next();
          }
          return null;
        }
      };
    }
    catch( IOException ex ) {
      throw new SolrException( SolrException.ErrorCode.SERVER_ERROR, ex );
    }
  }

  /**
   * @deprecated
   */
  public static List<Token> split( Matcher matcher, String input ){
    return split(matcher,input,null);
  }
  
  /**
   * This behaves just like String.split( ), but returns a list of Tokens
   * rather then an array of strings
   */
  public static List<Token> split( Matcher matcher, String input, Reader stream )
  {
    int index = 0;
    int lastNonEmptySize = Integer.MAX_VALUE;
    ArrayList<Token> matchList = new ArrayList<Token>();

    // Add segments before each match found
    while(matcher.find()) {
      String match = input.subSequence(index, matcher.start()).toString();
      matchList.add( newToken( stream, match, index, matcher.start()) );
      index = matcher.end();
      if( match.length() > 0 ) {
        lastNonEmptySize = matchList.size();
      }
    }

    // If no match is found, return the full string
    if (index == 0) {
      matchList.add( newToken( stream, input, 0, input.length()) );
    }
    else { 
      String match = input.subSequence(index, input.length()).toString();
      matchList.add( newToken( stream, match, index, input.length()) );
      if( match.length() > 0 ) {
        lastNonEmptySize = matchList.size();
      }
    }
    
    // Don't use trailing empty strings.  This behavior matches String.split();
    if( lastNonEmptySize < matchList.size() ) {
      return matchList.subList( 0, lastNonEmptySize );
    }
    return matchList;
  }
  

  /**
   * @deprecated
   */
  public static List<Token> group( Matcher matcher, String input, int group ){
    return group(matcher, input, group, null);
  }
  
  /**
   * Create tokens from the matches in a matcher 
   */
  public static List<Token> group( Matcher matcher, String input, int group, Reader stream )
  {
    ArrayList<Token> matchList = new ArrayList<Token>();
    while(matcher.find()) {
      Token t = newToken( stream,
        matcher.group(group), 
        matcher.start(group), 
        matcher.end(group) );
      matchList.add( t );
    }
    return matchList;
  }
  
  private static Token newToken(Reader reader, String text, int start, int end ){
    Token token;
    if( reader instanceof CharStream) {
      CharStream stream = (CharStream)reader;
      token = new Token( text, stream.correctOffset( start ), stream.correctOffset( end ) );
    } else {
      token = new Token( text, start, end );
    }
    return token;
  }
}
