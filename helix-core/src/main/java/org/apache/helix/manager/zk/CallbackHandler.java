package org.apache.helix.manager.zk;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.apache.helix.HelixConstants.ChangeType.CONFIG;
import static org.apache.helix.HelixConstants.ChangeType.CURRENT_STATE;
import static org.apache.helix.HelixConstants.ChangeType.EXTERNAL_VIEW;
import static org.apache.helix.HelixConstants.ChangeType.IDEAL_STATE;
import static org.apache.helix.HelixConstants.ChangeType.LIVE_INSTANCE;
import static org.apache.helix.HelixConstants.ChangeType.MESSAGE;
import static org.apache.helix.HelixConstants.ChangeType.MESSAGES_CONTROLLER;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.apache.helix.BaseDataAccessor;
import org.apache.helix.ConfigChangeListener;
import org.apache.helix.ControllerChangeListener;
import org.apache.helix.CurrentStateChangeListener;
import org.apache.helix.ExternalViewChangeListener;
import org.apache.helix.HelixConstants.ChangeType;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixProperty;
import org.apache.helix.IdealStateChangeListener;
import org.apache.helix.InstanceConfigChangeListener;
import org.apache.helix.LiveInstanceChangeListener;
import org.apache.helix.MessageListener;
import org.apache.helix.NotificationContext;
import org.apache.helix.NotificationContext.Type;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyPathConfig;
import org.apache.helix.ScopedConfigChangeListener;
import org.apache.helix.ZNRecord;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.Message;
import org.apache.log4j.Logger;
import org.apache.zookeeper.Watcher.Event.EventType;

//zhixin: listen on ZK changes and use enqueue/invoke task to process the changes
public class CallbackHandler implements IZkChildListener, IZkDataListener

{
  private static Logger logger = Logger.getLogger(CallbackHandler.class);

  /**
   * define the next possible notification types
   */
  private static Map<Type, List<Type>> nextNotificationType = new HashMap<Type, List<Type>>();
  static {
    nextNotificationType.put(Type.INIT, Arrays.asList(Type.CALLBACK, Type.FINALIZE));
    nextNotificationType.put(Type.CALLBACK, Arrays.asList(Type.CALLBACK, Type.FINALIZE));
    nextNotificationType.put(Type.FINALIZE, Arrays.asList(Type.INIT));
  }

  private final String _path;
  private final Object _listener;
  private final EventType[] _eventTypes;
  private final HelixDataAccessor _accessor;
  private final ChangeType _changeType;
  private final ZkClient _zkClient;
  private final AtomicLong _lastNotificationTimeStamp;
  private final HelixManager _manager;
  private final PropertyKey _propertyKey;
  BlockingQueue<NotificationContext> _queue = new LinkedBlockingQueue<NotificationContext>(1000);
  private static boolean asyncBatchModeEnabled = false;
  static {
    asyncBatchModeEnabled = Boolean.parseBoolean(System.getProperty("isAsyncBatchModeEnabled"));
    logger.info("isAsyncBatchModeEnabled: " + asyncBatchModeEnabled);
  }
  /**
   * maintain the expected notification types
   * this is fix for HELIX-195: race condition between FINALIZE callbacks and Zk callbacks
   */
  private List<NotificationContext.Type> _expectTypes = nextNotificationType.get(Type.FINALIZE);

  // zhixin: all listeners are registered with this method. ie. all onMessage() is called by this class
  // This class handles all of the ZK changes, TaskExecutor (and therefore DefaultMessagingService) also depends on this class,
  // and listens a subset of the Events/ChangeType (ChangeType.MESSAGE; EventType.NodeChildrenChanged, EventType.NodeDeleted, EventType.NodeCreated)
  public CallbackHandler(HelixManager manager, ZkClient client, PropertyKey propertyKey, Object listener, EventType[] eventTypes, ChangeType changeType) {
    if (listener == null) {
      throw new HelixException("listener could not be null");
    }

    this._manager = manager;
    this._accessor = manager.getHelixDataAccessor();
    this._zkClient = client;
    this._propertyKey = propertyKey;
    this._path = propertyKey.getPath();
    this._listener = listener;
    this._eventTypes = eventTypes;
    this._changeType = changeType;
    this._lastNotificationTimeStamp = new AtomicLong(System.nanoTime());
    this._queue = new LinkedBlockingQueue<NotificationContext>(1000);
    if (asyncBatchModeEnabled) {
      new Thread(new CallbackInvoker(this)).start();
    }
    init();
  }

  public Object getListener() {
    return _listener;
  }

  public String getPath() {
    return _path;
  }

  class CallbackInvoker implements Runnable {
    private CallbackHandler handler;

    CallbackInvoker(CallbackHandler handler) {
      this.handler = handler;
    }

    public void run() {
      while (true) {
        try {
          NotificationContext notificationToProcess = _queue.take();
          int mergedCallbacks = 0;
          // remove all elements in the queue that have the same type
          while (true) {
            NotificationContext nextItem = _queue.peek();
            if (nextItem != null && notificationToProcess.getType() == nextItem.getType()) {
              notificationToProcess = _queue.take();
              mergedCallbacks++;
            } else {
              break;
            }
          }
          try {
            logger.info("Num callbacks merged for path:" + handler.getPath() + " : " + mergedCallbacks);
            handler.invoke(notificationToProcess);
          } catch (Exception e) {
            logger.warn("Exception in callback processing thread. Skipping callback", e);
          }
        } catch (InterruptedException e) {
          logger.warn("Interrupted exception in callback processing thread. Exiting thread, new callbacks will not be processed", e);
          break;
        }
      }
    }
  }

  public void enqueueTask(NotificationContext changeContext) throws Exception {
    //async mode only applicable to CALLBACK from ZK, During INIT and FINALIZE invoke the callback's immediately.
    if (asyncBatchModeEnabled && changeContext.getType() == NotificationContext.Type.CALLBACK) {
      logger.info("Enqueuing callback");
      _queue.put(changeContext);
    } else {
      invoke(changeContext);
    }
  }

  public void invoke(NotificationContext changeContext) throws Exception {
    // This allows the listener to work with one change at a time
    synchronized (_manager) {
      Type type = changeContext.getType();
      if (!_expectTypes.contains(type)) {
        logger.warn("Skip processing callbacks for listener: " + _listener + ", path: " + _path + ", expected types: " + _expectTypes + " but was " + type);
        return;
      }
      _expectTypes = nextNotificationType.get(type);

      // Builder keyBuilder = _accessor.keyBuilder();
      long start = System.currentTimeMillis();
      if (logger.isInfoEnabled()) {
        logger.info(Thread.currentThread().getId() + " START:INVOKE " + _path + " listener:" + _listener.getClass().getCanonicalName());
      }

      if (_changeType == IDEAL_STATE) {

        IdealStateChangeListener idealStateChangeListener = (IdealStateChangeListener) _listener;
        subscribeForChanges(changeContext, _path, true, true);
        List<IdealState> idealStates = _accessor.getChildValues(_propertyKey);

        idealStateChangeListener.onIdealStateChange(idealStates, changeContext);

      } else if (_changeType == ChangeType.INSTANCE_CONFIG) {
        subscribeForChanges(changeContext, _path, true, true);
        if (_listener instanceof ConfigChangeListener) {
          ConfigChangeListener configChangeListener = (ConfigChangeListener) _listener;
          List<InstanceConfig> configs = _accessor.getChildValues(_propertyKey);
          configChangeListener.onConfigChange(configs, changeContext);
        } else if (_listener instanceof InstanceConfigChangeListener) {
          InstanceConfigChangeListener listener = (InstanceConfigChangeListener) _listener;
          List<InstanceConfig> configs = _accessor.getChildValues(_propertyKey);
          listener.onInstanceConfigChange(configs, changeContext);
        }
      } else if (_changeType == CONFIG) {
        subscribeForChanges(changeContext, _path, true, true);
        ScopedConfigChangeListener listener = (ScopedConfigChangeListener) _listener;
        List<HelixProperty> configs = _accessor.getChildValues(_propertyKey);
        listener.onConfigChange(configs, changeContext);
      } else if (_changeType == LIVE_INSTANCE) {
        LiveInstanceChangeListener liveInstanceChangeListener = (LiveInstanceChangeListener) _listener;
        subscribeForChanges(changeContext, _path, true, true);
        List<LiveInstance> liveInstances = _accessor.getChildValues(_propertyKey);

        liveInstanceChangeListener.onLiveInstanceChange(liveInstances, changeContext);

      } else if (_changeType == CURRENT_STATE) {
        CurrentStateChangeListener currentStateChangeListener = (CurrentStateChangeListener) _listener;
        subscribeForChanges(changeContext, _path, true, true);
        String instanceName = PropertyPathConfig.getInstanceNameFromPath(_path);

        List<CurrentState> currentStates = _accessor.getChildValues(_propertyKey);

        currentStateChangeListener.onStateChange(instanceName, currentStates, changeContext);

      } else if (_changeType == MESSAGE) {
        MessageListener messageListener = (MessageListener) _listener;
        subscribeForChanges(changeContext, _path, true, false);
        String instanceName = PropertyPathConfig.getInstanceNameFromPath(_path);
        List<Message> messages = _accessor.getChildValues(_propertyKey);

        messageListener.onMessage(instanceName, messages, changeContext);

      } else if (_changeType == MESSAGES_CONTROLLER) {
        MessageListener messageListener = (MessageListener) _listener;
        subscribeForChanges(changeContext, _path, true, false);
        List<Message> messages = _accessor.getChildValues(_propertyKey);

        messageListener.onMessage(_manager.getInstanceName(), messages, changeContext);

      } else if (_changeType == EXTERNAL_VIEW) {
        ExternalViewChangeListener externalViewListener = (ExternalViewChangeListener) _listener;
        subscribeForChanges(changeContext, _path, true, true);
        List<ExternalView> externalViewList = _accessor.getChildValues(_propertyKey);

        externalViewListener.onExternalViewChange(externalViewList, changeContext);
      } else if (_changeType == ChangeType.CONTROLLER) {
        ControllerChangeListener controllerChangelistener = (ControllerChangeListener) _listener;
        subscribeForChanges(changeContext, _path, true, false);
        controllerChangelistener.onControllerChange(changeContext);
      }

      long end = System.currentTimeMillis();
      if (logger.isInfoEnabled()) {
        logger.info(Thread.currentThread().getId() + " END:INVOKE " + _path + " listener:" + _listener.getClass().getCanonicalName() + " Took: " + (end - start)
            + "ms");
      }
    }
  }

  private void subscribeChildChange(String path, NotificationContext context) {
    NotificationContext.Type type = context.getType();
    if (type == NotificationContext.Type.INIT || type == NotificationContext.Type.CALLBACK) {
      logger.info(_manager.getInstanceName() + " subscribes child-change. path: " + path + ", listener: " + _listener);
      _zkClient.subscribeChildChanges(path, this);
    } else if (type == NotificationContext.Type.FINALIZE) {
      logger.info(_manager.getInstanceName() + " unsubscribe child-change. path: " + path + ", listener: " + _listener);

      _zkClient.unsubscribeChildChanges(path, this);
    }
  }

  private void subscribeDataChange(String path, NotificationContext context) {
    NotificationContext.Type type = context.getType();
    if (type == NotificationContext.Type.INIT || type == NotificationContext.Type.CALLBACK) {
      if (logger.isDebugEnabled()) {
        logger.debug(_manager.getInstanceName() + " subscribe data-change. path: " + path + ", listener: " + _listener);
      }
      _zkClient.subscribeDataChanges(path, this);

    } else if (type == NotificationContext.Type.FINALIZE) {
      logger.info(_manager.getInstanceName() + " unsubscribe data-change. path: " + path + ", listener: " + _listener);

      _zkClient.unsubscribeDataChanges(path, this);
    }
  }

  // TODO watchParent is always true. consider remove it
  private void subscribeForChanges(NotificationContext context, String path, boolean watchParent, boolean watchChild) {
    long start = System.currentTimeMillis();
    if (watchParent) {
      subscribeChildChange(path, context);
    }

    if (watchChild) {
      try {
        switch (_changeType) {
        case CURRENT_STATE:
        case IDEAL_STATE:
        case EXTERNAL_VIEW: {
          // check if bucketized
          BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(_zkClient);
          List<ZNRecord> records = baseAccessor.getChildren(path, null, 0);
          for (ZNRecord record : records) {
            HelixProperty property = new HelixProperty(record);
            String childPath = path + "/" + record.getId();

            int bucketSize = property.getBucketSize();
            if (bucketSize > 0) {
              // subscribe both data-change and child-change on bucketized parent node
              // data-change gives a delete-callback which is used to remove watch
              subscribeChildChange(childPath, context);
              subscribeDataChange(childPath, context);

              // subscribe data-change on bucketized child
              List<String> bucketizedChildNames = _zkClient.getChildren(childPath);
              if (bucketizedChildNames != null) {
                for (String bucketizedChildName : bucketizedChildNames) {
                  String bucketizedChildPath = childPath + "/" + bucketizedChildName;
                  subscribeDataChange(bucketizedChildPath, context);
                }
              }
            } else {
              subscribeDataChange(childPath, context);
            }
          }
          break;
        }
        default: {
          List<String> childNames = _zkClient.getChildren(path);
          if (childNames != null) {
            for (String childName : childNames) {
              String childPath = path + "/" + childName;
              subscribeDataChange(childPath, context);
            }
          }
          break;
        }
        }
      } catch (ZkNoNodeException e) {
        logger.warn("fail to subscribe child/data change. path: " + path + ", listener: " + _listener, e);
      }
    }
    long end = System.currentTimeMillis();
    logger.info("Subcribing to path:" + path + " took:" + (end - start));

  }

  public EventType[] getEventTypes() {
    return _eventTypes;
  }

  /**
   * Invoke the listener so that it sets up the initial values from the zookeeper if any
   * exists
   */
  public void init() {
    updateNotificationTime(System.nanoTime());
    try {
      NotificationContext changeContext = new NotificationContext(_manager);
      changeContext.setType(NotificationContext.Type.INIT);
      enqueueTask(changeContext);
    } catch (Exception e) {
      String msg = "Exception while invoking init callback for listener:" + _listener;
      ZKExceptionHandler.getInstance().handle(msg, e);
    }
  }

  @Override
  public void handleDataChange(String dataPath, Object data) {
    try {
      updateNotificationTime(System.nanoTime());
      if (dataPath != null && dataPath.startsWith(_path)) {
        NotificationContext changeContext = new NotificationContext(_manager);
        changeContext.setType(NotificationContext.Type.CALLBACK);
        enqueueTask(changeContext);
      }
    } catch (Exception e) {
      String msg = "exception in handling data-change. path: " + dataPath + ", listener: " + _listener;
      ZKExceptionHandler.getInstance().handle(msg, e);
    }
  }

  @Override
  public void handleDataDeleted(String dataPath) {
    try {
      updateNotificationTime(System.nanoTime());
      if (dataPath != null && dataPath.startsWith(_path)) {
        logger.info(_manager.getInstanceName() + " unsubscribe data-change. path: " + dataPath + ", listener: " + _listener);
        _zkClient.unsubscribeDataChanges(dataPath, this);

        // only needed for bucketized parent, but OK if we don't have child-change
        // watch on the bucketized parent path
        logger.info(_manager.getInstanceName() + " unsubscribe child-change. path: " + dataPath + ", listener: " + _listener);
        _zkClient.unsubscribeChildChanges(dataPath, this);
        // No need to invoke() since this event will handled by child-change on parent-node
        // NotificationContext changeContext = new NotificationContext(_manager);
        // changeContext.setType(NotificationContext.Type.CALLBACK);
        // invoke(changeContext);
      }
    } catch (Exception e) {
      String msg = "exception in handling data-delete-change. path: " + dataPath + ", listener: " + _listener;
      ZKExceptionHandler.getInstance().handle(msg, e);
    }
  }

  @Override
  public void handleChildChange(String parentPath, List<String> currentChilds) {
    try {
      updateNotificationTime(System.nanoTime());
      if (parentPath != null && parentPath.startsWith(_path)) {
        NotificationContext changeContext = new NotificationContext(_manager);

        if (currentChilds == null && parentPath.equals(_path)) {
          // _path has been removed, remove this listener
          // removeListener will call handler.reset(), which in turn call invoke() on FINALIZE type
          _manager.removeListener(_propertyKey, _listener);
        } else {
          changeContext.setType(NotificationContext.Type.CALLBACK);
          enqueueTask(changeContext);
        }
      }
    } catch (Exception e) {
      String msg = "exception in handling child-change. instance: " + _manager.getInstanceName() + ", parentPath: " + parentPath + ", listener: " + _listener;
      ZKExceptionHandler.getInstance().handle(msg, e);
    }
  }

  /**
   * Invoke the listener for the last time so that the listener could clean up resources
   */
  public void reset() {
    try {
      NotificationContext changeContext = new NotificationContext(_manager);
      changeContext.setType(NotificationContext.Type.FINALIZE);
      enqueueTask(changeContext);
    } catch (Exception e) {
      String msg = "Exception while resetting the listener:" + _listener;
      ZKExceptionHandler.getInstance().handle(msg, e);
    }
  }

  private void updateNotificationTime(long nanoTime) {
    long l = _lastNotificationTimeStamp.get();
    while (nanoTime > l) {
      boolean b = _lastNotificationTimeStamp.compareAndSet(l, nanoTime);
      if (b) {
        break;
      } else {
        l = _lastNotificationTimeStamp.get();
      }
    }
  }

}
