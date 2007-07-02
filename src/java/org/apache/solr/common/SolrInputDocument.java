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

package org.apache.solr.common;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Represent the field and boost information needed to construct and index
 * a Lucene Document.  Like the SolrDocument, the field values should
 * match those specified in schema.xml 
 * 
 * By default, this will keep every field value added to the document.  To only
 * keep distinct values, use setRemoveDuplicateFieldValues( "fieldname", true );
 *
 * @author ryan
 * @version $Id$
 * @since solr 1.3
 */
public class SolrInputDocument implements Iterable<SolrInputField>
{
  private final Map<String,SolrInputField> _fields;
  private Map<String,Boolean> _removeDuplicates = null;
  private float _documentBoost = 1.0f;

  public SolrInputDocument()
  {
    _fields = new HashMap<String,SolrInputField>();
  }
  
  /**
   * Remove all fields and boosts from the document
   */
  public void clear()
  {
    if( _fields != null ) {
      _fields.clear();
    }
    if(_removeDuplicates != null ) {
      _removeDuplicates.clear();
    }
  }

  ///////////////////////////////////////////////////////////////////
  // Add / Set fields
  ///////////////////////////////////////////////////////////////////

  private boolean isDistinct( String name )
  {
    if( _removeDuplicates != null ) {
      Boolean v = _removeDuplicates.get( name );
      if( v == null ) {
        v = _removeDuplicates.get( null );
      }
      return (v == Boolean.TRUE);
    }
    return false;
  }
  
  public void setField(String name, Object value, Float boost ) 
  {
    SolrInputField field = new SolrInputField( name );
    _fields.put( name, field );
    if( isDistinct( name ) ) {
      field.value = new LinkedHashSet<Object>();
      this.addField(name, value, boost);
    }
    else {
      field.setValue( value, boost );
    }
  }

  /**
   * Remove all fields and boosts from the document
   */
  public void addField(String name, Object value, Float boost ) 
  {
    SolrInputField field = _fields.get( name );
    if( field == null || field.value == null ) {
      setField(name, value, boost);
    }
    else {
      field.addValue( value, boost );
    }
  }

  public boolean removeField(String name) {
    if( name != null ) {
      return _fields.remove( name ) != null;
    }
    return false;
  }
  
  /**
   * Should the Document be able to contain duplicate values for the same field?
   * 
   * By default, all field values are maintained.  If you only want to distinct values
   * set setKeepDuplicateFieldValues( "fieldname", false );
   * 
   * To change the default behavior, use <code>null</code> as the fieldname.
   * 
   * NOTE: this must be called before adding any values to the given field.
   */
  public void setRemoveDuplicateFieldValues( String name, boolean v )
  {
    if( _fields.get( name ) != null ) {
      // If it was not distinct and changed to distinct, we could, but this seems like a better rule
      throw new RuntimeException( "You can't change a fields distinctness after it is initialized." );
    }
    
    if( _removeDuplicates == null ) {
      if( v == false ) {
        // we only care about 'true'  we don't need to make a map unless 
        // something does not want multiple values
        return; 
      }
      _removeDuplicates = new HashMap<String, Boolean>();
    }
    _removeDuplicates.put( name, v );
  }

  ///////////////////////////////////////////////////////////////////
  // Get the field values
  ///////////////////////////////////////////////////////////////////

  public SolrInputField getField( String field )
  {
    return _fields.get( field );
  }

  public Iterator<SolrInputField> iterator() {
    return _fields.values().iterator();
  }
  
  public float getDocumentBoost() {
    return _documentBoost;
  }

  public void setDocumentBoost(float documentBoost) {
    _documentBoost = documentBoost;
  }
  
  @Override
  public String toString()
  {
    return "SolrInputDocumnt["+_fields+"]";
  }
}
