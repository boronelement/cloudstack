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

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;

/*
 * synonym to the pool python lib in the ovs-agent
 */
public class Pool extends OvmObject {
    private static final Logger LOGGER = Logger
            .getLogger(Pool.class);

    private final List<String> validRoles = new ArrayList<String>() {
        {
            add("xen");
            add("utility");
        }
    };
    private List<String> poolHosts = new ArrayList<String>();
    private final List<String> poolRoles = new ArrayList<String>();
    private final List<String> poolMembers = new ArrayList<String>();
    private String poolMasterVip;
    private String poolAlias;
    private String poolId = null;

    public Pool(Connection c) {
        setClient(c);
    }

    public String getPoolMasterVip() {
        return poolMasterVip;
    }

    public String getPoolAlias() {
        return poolAlias;
    }

    public String getPoolId() {
        return poolId;
    }

    public List<String> getValidRoles() {
        return validRoles;
    }

    public Boolean isInPool(String id) throws Ovm3ResourceException {
        if (poolId == null) {
            discoverServerPool();
        }
        if (poolId == null) {
            return false;
        }
        if (isInAPool() && poolId.equals(id)) {
            return true;
        }
        return false;
    }

    public Boolean isInAPool() throws Ovm3ResourceException {
        if (poolId == null) {
            discoverServerPool();
        }
        if (poolId == null) {
            return false;
        }
        return true;
    }

    private Boolean createServerPool(String alias, String id, String vip,
            int num, String name, String host, List<String> roles) throws Ovm3ResourceException{
        String role = StringUtils.join(roles, ",");
        if (!isInAPool()) {
            Object x = callWrapper("create_server_pool", alias, id, vip, num, name,
                    host, role);
            if (x == null) {
                return true;
            }
            return false;
        } else if (isInPool(id)) {
            return true;
        } else {
            throw new Ovm3ResourceException("Unable to add host is already in  a pool with id : " + poolId);
        }
    }

    public Boolean createServerPool(String alias, String id, String vip,
            int num, String name, String ip) throws Ovm3ResourceException {
        return createServerPool(alias, id, vip, num, name, ip,
                getValidRoles());
    }

    /*
     * public Boolean updatePoolVirtualIp(String ip) throws
     * Ovm3ResourceException { Object x = callWrapper("update_pool_virtual_ip",
     * ip); if (x == null) { poolMasterVip = ip; return true; } return false; }
     */

    public Boolean leaveServerPool(String uuid) throws Ovm3ResourceException{
        return nullIsTrueCallWrapper("leave_server_pool", uuid);
    }

    public Boolean takeOwnership(String uuid, String apiurl) throws Ovm3ResourceException {
        return nullIsTrueCallWrapper("take_ownership", uuid, apiurl);
    }

    /*
     * destroy_server_pool, <class 'agent.api.serverpool.ServerPool'> argument:
     * self - default: None argument: pool_uuid - default: None
     */
    public Boolean destroyServerPool(String uuid) throws Ovm3ResourceException{
        return nullIsTrueCallWrapper("destroy_server_pool", uuid);
    }

    /*
     * release_ownership, <class 'agent.api.serverpool.ServerPool'> argument:
     * self - default: None argument: manager_uuid - default: None
     */
    public Boolean releaseOwnership(String uuid) throws Ovm3ResourceException{
        return nullIsTrueCallWrapper("release_ownership", uuid);
    }

    /* server.discover_pool_filesystem */
    /*
     * discover_server_pool, <class 'agent.api.serverpool.ServerPool'> argument:
     * self - default: None
     */
    public Boolean discoverServerPool() throws Ovm3ResourceException {
        Object x = callWrapper("discover_server_pool");
        if (x == null) {
            return false;
        }

        Document xmlDocument = prepParse((String) x);
        String path = "//Discover_Server_Pool_Result/Server_Pool";
        poolId = xmlToString(path + "/Unique_Id", xmlDocument);
        poolAlias = xmlToString(path + "/Pool_Alias", xmlDocument);
        poolMasterVip = xmlToString(path + "/Master_Virtual_Ip",
                xmlDocument);
        // this.setPoolMembers(xmlToList(path + "/Member_List", xmlDocument));
        poolHosts.addAll(xmlToList(path + "//Registered_IP", xmlDocument));
        if (poolId == null) {
            return false;
        }
        return true;
    }

    public Boolean setServerRoles() throws Ovm3ResourceException{
        String roles = StringUtils.join(poolRoles.toArray(), ",");
        return nullIsTrueCallWrapper("update_server_roles", roles);
    }

    /* do some sanity check on the valid poolroles */
    public Boolean setServerRoles(List<String> roles) throws Ovm3ResourceException {
        poolRoles.addAll(roles);
        return setServerRoles();
    }

    public Boolean joinServerPool(String alias, String id, String vip, int num,
            String name, String host, List<String> roles) throws Ovm3ResourceException{
        String role = StringUtils.join(roles.toArray(), ",");
        if (!isInAPool()) {
            Object x = callWrapper("join_server_pool", alias, id, vip, num, name,
                    host, role);
            if (x == null) {
                return true;
            }
            return false;
        } else if (isInPool(id)) {
            return true;
        } else {
            throw new Ovm3ResourceException("Unable to add host is already in  a pool with id : " + poolId);
        }
    }

    public Boolean joinServerPool(String alias, String id, String vip, int num,
            String name, String host) throws Ovm3ResourceException {
        return joinServerPool(alias, id, vip, num, name, host, getValidRoles());
    }

    private Boolean setPoolMemberList() throws Ovm3ResourceException {
        // should throw exception if no poolHosts set
        return nullIsTrueCallWrapper("set_pool_member_ip_list", poolHosts);
    }

    public List<String> getPoolMemberList() throws Ovm3ResourceException {
        if (poolId == null) {
            discoverServerPool();
        }
        return poolHosts;
    }

    public Boolean setPoolMemberList(List<String> hosts) throws Ovm3ResourceException {
        poolHosts = new ArrayList<String>();
        poolHosts.addAll(hosts);
        return setPoolMemberList();
    }

    public Boolean addPoolMember(String host) throws Ovm3ResourceException {
        getPoolMemberList();
        poolHosts.add(host);
        return setPoolMemberList();
    }

    public Boolean removePoolMember(String host) throws Ovm3ResourceException {
        getPoolMemberList();
        poolHosts.remove(host);
        return setPoolMemberList();
    }
}
