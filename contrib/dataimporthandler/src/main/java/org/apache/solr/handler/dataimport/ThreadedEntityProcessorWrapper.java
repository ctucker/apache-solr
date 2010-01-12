package org.apache.solr.handler.dataimport;

import static org.apache.solr.handler.dataimport.EntityProcessorBase.ON_ERROR;
import static org.apache.solr.handler.dataimport.EntityProcessorBase.ABORT;
import static org.apache.solr.handler.dataimport.DataImportHandlerException.wrapAndThrow;
import static org.apache.solr.handler.dataimport.DataImportHandlerException.SEVERE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/**
 * Each Entity may have only a single EntityProcessor .  But the same entity can be run by
 * multiple EntityProcessorWrapper (1 per thread) . thhis helps running transformations in multiple threads
 */

public class ThreadedEntityProcessorWrapper extends EntityProcessorWrapper {
  private static final Logger LOG = LoggerFactory.getLogger(ThreadedEntityProcessorWrapper.class);

  DocBuilder.EntityRunner entityRunner;
  /**For each child entity there is one EntityRunner
   */
  Map<DataConfig.Entity ,DocBuilder.EntityRunner> children;

  public ThreadedEntityProcessorWrapper(EntityProcessor delegate, DocBuilder docBuilder,
                                  DocBuilder.EntityRunner entityRunner,
                                  VariableResolverImpl resolver) {
    super(delegate, docBuilder);
    this.entityRunner = entityRunner;
    this.resolver = resolver;
    if (entityRunner.entity.entities == null) {
      children = Collections.emptyMap();
    } else {
      children = new HashMap<DataConfig.Entity, DocBuilder.EntityRunner>(entityRunner.entity.entities.size());
      for (DataConfig.Entity e : entityRunner.entity.entities) {
        DocBuilder.EntityRunner runner = docBuilder.createRunner(e, entityRunner);
        children.put(e, runner);
      }
    }

  }

  void threadedInit(Context context){
    rowcache = null;
    this.context = context;
    resolver = (VariableResolverImpl) context.getVariableResolver();
    //context has to be set correctly . keep the copy of the old one so that it can be restored in destroy
    if (entityName == null) {
      onError = resolver.replaceTokens(context.getEntityAttribute(ON_ERROR));
      if (onError == null) onError = ABORT;
      entityName = context.getEntityAttribute(DataConfig.NAME);
    }    
  }

  @Override
  public Map<String, Object> nextRow() {
    if (rowcache != null) {
      return getFromRowCache();
    }
    while (true) {
      Map<String, Object> arow = null;
      synchronized (delegate) {
        if(entityRunner.entityEnded.get()) return null;
        try {
          arow = delegate.nextRow();
        } catch (Exception e) {
          if (ABORT.equals(onError)) {
            wrapAndThrow(SEVERE, e);
          } else {
            //SKIP is not really possible. If this calls the nextRow() again the Entityprocessor would be in an inconisttent state
            LOG.error("Exception in entity : " + entityName, e);
            return null;
          }
        }
        LOG.info("arow : "+arow);
        if(arow == null) entityRunner.entityEnded.set(true);
      }
      if (arow == null) {
        return null;
      } else {
        arow = applyTransformer(arow);
        if (arow != null) {
          delegate.postTransform(arow);
          return arow;
        }
      }
    } 
  }

  public void init(DocBuilder.EntityRow rows) {
    for (DocBuilder.EntityRow row = rows; row != null; row = row.tail) resolver.addNamespace(row.name, row.row);
  }


 
}
