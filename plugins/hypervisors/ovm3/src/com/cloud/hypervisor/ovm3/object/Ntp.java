/*******************************************************************************
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.cloud.hypervisor.ovm3.object;

import java.util.ArrayList;
import java.util.List;

/*
 * should become an interface implementation
 */
public class Ntp extends OvmObject {
    private List<String> ntpHosts = new ArrayList<String>();
    private Boolean isServer = null;
    private Boolean isRunning = null;

    public Ntp(Connection c) {
        setClient(c);
    }

    public List<String> addServer(String server) {
        if (!ntpHosts.contains(server)) {
            ntpHosts.add(server);
        }
        return ntpHosts;
    }

    public List<String> removeServer(String server) {
        if (ntpHosts.contains(server)) {
            ntpHosts.remove(server);
        }
        return ntpHosts;
    }

    public List<String> getServers() {
        return ntpHosts;
    }

    public Boolean isRunning() {
        return isRunning;
    }

    public Boolean isServer() {
        return isServer;
    }

    public Boolean getDetails() throws Ovm3ResourceException {
        return this.getNtp();
    }

    /*
     * get_ntp, <class 'agent.api.host.linux.Linux'> argument: self - default:
     * None
     */
    public Boolean getNtp() throws Ovm3ResourceException {
        Object[] v = (Object[]) callWrapper("get_ntp");
        int c = 0;
        for (Object o : v) {
            if (o instanceof java.lang.Boolean) {
                if (c == 0) {
                    this.isServer = (Boolean) o;
                }
                if (c == 1) {
                    this.isRunning = (Boolean) o;
                }
                // should not get here
                if (c > 1) {
                    return false;
                }
                c += 1;
            } else if (o instanceof java.lang.Object) {
                Object[] s = (Object[]) o;
                for (Object m : s) {
                    this.addServer((String) m);
                }
            }
        }
        return true;
    }

    /*
     * set_ntp, <class 'agent.api.host.linux.Linux'> argument: self - default:
     * None argument: ntpHosts - default: None argument: local_time_source -
     * default: None argument: allow_query - default: None // right, can't be
     * set eh
     */
    public Boolean setNtp(List<String> ntpHosts, Boolean running)
            throws Ovm3ResourceException {
        return nullIsTrueCallWrapper("set_ntp", ntpHosts, running);
    }

    /* also cleans the vector */
    public Boolean setNtp(String server, Boolean running)
            throws Ovm3ResourceException {
        this.ntpHosts = new ArrayList<String>();
        this.ntpHosts.add(server);
        return setNtp(this.ntpHosts, running);
    }

    public Boolean setNtp(Boolean running) throws Ovm3ResourceException {
        return setNtp(this.ntpHosts, running);
    }

    /*
     * disable_ntp, <class 'agent.api.host.linux.Linux'> argument: self -
     * default: None
     */
    public Boolean disableNtp() throws Ovm3ResourceException {
        return nullIsTrueCallWrapper("disable_ntp");

    }

    /*
     * enable_ntp, <class 'agent.api.host.linux.Linux'> argument: self -
     * default: None
     */
    public Boolean enableNtp() throws Ovm3ResourceException {
        return nullIsTrueCallWrapper("enable_ntp");
    }
}
