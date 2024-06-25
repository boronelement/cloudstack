// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.network;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
<<<<<<< HEAD
import org.apache.cloudstack.framework.events.EventBus;
import org.apache.cloudstack.framework.events.EventBusException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
=======
import org.apache.cloudstack.framework.events.EventDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002

import com.cloud.event.EventCategory;
import com.cloud.network.Network.Event;
import com.cloud.network.Network.State;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;

public class NetworkStateListener implements StateListener<State, Event, Network> {

    @Inject
    private ConfigurationDao _configDao;

<<<<<<< HEAD
    private static EventBus s_eventBus = null;
=======
    private EventDistributor eventDistributor;
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002

    protected Logger logger = LogManager.getLogger(getClass());

    public NetworkStateListener(ConfigurationDao configDao) {
        _configDao = configDao;
    }

<<<<<<< HEAD
=======
    public void setEventDistributor(EventDistributor eventDistributor) {
        this.eventDistributor = eventDistributor;
    }

>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002
    @Override
    public boolean preStateTransitionEvent(State oldState, Event event, State newState, Network vo, boolean status, Object opaque) {
        pubishOnEventBus(event.name(), "preStateTransitionEvent", vo, oldState, newState);
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<State, Event> transition, Network vo, boolean status, Object opaque) {
      State oldState = transition.getCurrentState();
      State newState = transition.getToState();
      Event event = transition.getEvent();
      pubishOnEventBus(event.name(), "postStateTransitionEvent", vo, oldState, newState);
      return true;
    }

<<<<<<< HEAD
  private void pubishOnEventBus(String event, String status, Network vo, State oldState, State newState) {

=======
    private void pubishOnEventBus(String event, String status, Network vo, State oldState, State newState) {
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002
        String configKey = "publish.resource.state.events";
        String value = _configDao.getValue(configKey);
        boolean configValue = Boolean.parseBoolean(value);
        if(!configValue)
            return;
<<<<<<< HEAD
        try {
            s_eventBus = ComponentContext.getComponent(EventBus.class);
        } catch (NoSuchBeanDefinitionException nbe) {
            return; // no provider is configured to provide events bus, so just return
=======
        if (eventDistributor == null) {
            setEventDistributor(ComponentContext.getComponent(EventDistributor.class));
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002
        }

        String resourceName = getEntityFromClassName(Network.class.getName());
        org.apache.cloudstack.framework.events.Event eventMsg =
<<<<<<< HEAD
            new org.apache.cloudstack.framework.events.Event("management-server", EventCategory.RESOURCE_STATE_CHANGE_EVENT.getName(), event, resourceName, vo.getUuid());
        Map<String, String> eventDescription = new HashMap<String, String>();
=======
              new org.apache.cloudstack.framework.events.Event("management-server", EventCategory.RESOURCE_STATE_CHANGE_EVENT.getName(), event, resourceName, vo.getUuid());
        Map<String, String> eventDescription = new HashMap<>();
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002
        eventDescription.put("resource", resourceName);
        eventDescription.put("id", vo.getUuid());
        eventDescription.put("old-state", oldState.name());
        eventDescription.put("new-state", newState.name());

        String eventDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date());
        eventDescription.put("eventDateTime", eventDate);

        eventMsg.setDescription(eventDescription);
<<<<<<< HEAD
        try {
            s_eventBus.publish(eventMsg);
        } catch (EventBusException e) {
            logger.warn("Failed to publish state change event on the event bus.");
        }
=======

        eventDistributor.publish(eventMsg);
>>>>>>> 9e53596ba92eaec1289e97bfc9f441cc3c507002
    }

    private String getEntityFromClassName(String entityClassName) {
        int index = entityClassName.lastIndexOf(".");
        String entityName = entityClassName;
        if (index != -1) {
            entityName = entityClassName.substring(index + 1);
        }
        return entityName;
    }
}
