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

package org.apache.cloudstack.framework.events;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.component.ManagerBase;

public class EventDistributorImpl extends ManagerBase implements EventDistributor {

    List<EventBus> eventBuses;

    public void setEventBuses(List<EventBus> eventBuses) {
        this.eventBuses = eventBuses;
    }

    @PostConstruct
    public void init() {
        if (logger.isTraceEnabled()) {
            logger.trace("Found {} event buses : {}", eventBuses.size(),
                    StringUtils.join(eventBuses.stream().map(x->x.getClass().getName()).toArray()));
        }
    }

    @Override
    public Map<String, EventBusException> publish(Event event) {
        Map<String, EventBusException> exceptions = new HashMap<>();
        if (event == null) {
            return exceptions;
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Publishing event [category: {}, type: {}]: {} to {} event buses",
                    event.getEventCategory(), event.getEventType(),
                    event.getDescription(), eventBuses.size());
        }
        for (EventBus bus : eventBuses) {
            try {
                bus.publish(event);
            } catch (EventBusException e) {
                logger.warn("Failed to publish for bus {} of event [category: {}, type: {}]",
                        bus.getName(), event.getEventCategory(), event.getEventType());
                if (logger.isTraceEnabled()) {
                    logger.trace(event.getDescription());
                }
                exceptions.put(bus.getName(), e);
            }
        }
        return exceptions;
    }

}
