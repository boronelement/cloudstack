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
package org.apache.cloudstack.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.kubernetes.cluster.KubernetesClusterHelper;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.Site2SiteCustomerGatewayDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.annotation.AddAnnotationCmd;
import org.apache.cloudstack.api.command.admin.annotation.ListAnnotationsCmd;
import org.apache.cloudstack.api.command.admin.annotation.RemoveAnnotationCmd;
import org.apache.cloudstack.api.command.admin.annotation.UpdateAnnotationVisibilityCmd;
import org.apache.cloudstack.api.response.AnnotationResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.log4j.Logger;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * @since 4.11
 */
public final class AnnotationManagerImpl extends ManagerBase implements AnnotationService, Configurable, PluggableService {
    public static final Logger LOGGER = Logger.getLogger(AnnotationManagerImpl.class);

    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private UserDao userDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private RoleService roleService;
    @Inject
    private AccountService accountService;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private InstanceGroupDao instanceGroupDao;
    @Inject
    private SSHKeyPairDao sshKeyPairDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private Site2SiteCustomerGatewayDao customerGatewayDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private HostPodDao hostPodDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private ImageStoreDao imageStoreDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;

    private static final List<RoleType> adminRoles = Collections.singletonList(RoleType.Admin);
    private List<KubernetesClusterHelper> kubernetesClusterHelpers;

    public List<KubernetesClusterHelper> getKubernetesClusterHelpers() {
        return kubernetesClusterHelpers;
    }

    public void setKubernetesClusterHelpers(final List<KubernetesClusterHelper> kubernetesClusterHelpers) {
        this.kubernetesClusterHelpers = kubernetesClusterHelpers;
    }

    @Override
    public boolean start() {
        super.start();
        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return true;
    }

    @Override
    public ListResponse<AnnotationResponse> searchForAnnotations(ListAnnotationsCmd cmd) {
        Pair<List<AnnotationVO>, Integer> annotations = getAnnotationsForApiCmd(cmd);
        List<AnnotationResponse> annotationResponses = convertAnnotationsToResponses(annotations.first());
        return createAnnotationsResponseList(annotationResponses, annotations.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ANNOTATION_CREATE, eventDescription = "creating an annotation on an entity")
    public AnnotationResponse addAnnotation(AddAnnotationCmd addAnnotationCmd) {
        return addAnnotation(addAnnotationCmd.getAnnotation(), addAnnotationCmd.getEntityType(),
                addAnnotationCmd.getEntityUuid(), addAnnotationCmd.isAdminsOnly());
    }

    public AnnotationResponse addAnnotation(String text, EntityType type, String uuid, boolean adminsOnly) {
        UserVO userVO = getCallingUserFromContext();
        String userUuid = userVO.getUuid();
        checkAnnotationPermissions(uuid, type, userVO);

        AnnotationVO annotation = new AnnotationVO(text, type, uuid, adminsOnly);
        annotation.setUserUuid(userUuid);
        annotation = annotationDao.persist(annotation);
        return createAnnotationResponse(annotation);
    }

    private void checkAnnotationPermissions(String entityUuid, EntityType type, UserVO user) {
        if (isCallingUserAdmin()) {
            return;
        }
        if (!type.isUserAllowed()) {
            throw new CloudRuntimeException(String.format("User: %s is not allowed to add annotations on type: %s",
                    user.getUsername(), type.name()));
        }
        ensureEntityIsOwnedByTheUser(type.name(), entityUuid, user);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ANNOTATION_REMOVE, eventDescription = "removing an annotation on an entity")
    public AnnotationResponse removeAnnotation(RemoveAnnotationCmd removeAnnotationCmd) {
        String uuid = removeAnnotationCmd.getUuid();
        AnnotationVO annotation = annotationDao.findByUuid(uuid);
        if (!isCallingUserAllowedToRemoveAnnotation(annotation)) {
            throw new CloudRuntimeException(String.format("Only administrators or entity owner users can delete annotations, " +
                    "cannot remove annotation with uuid: %s - type: %s ", uuid, annotation.getEntityType().name()));
        }
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Removing annotation uuid: %s - type: %s", uuid, annotation.getEntityType().name()));
        }
        annotationDao.remove(annotation.getId());

        return createAnnotationResponse(annotation);
    }

    @Override
    public AnnotationResponse updateAnnotationVisibility(UpdateAnnotationVisibilityCmd cmd) {
        String uuid = cmd.getUuid();
        Boolean adminsOnly = cmd.getAdminsOnly();
        AnnotationVO annotation = annotationDao.findByUuid(uuid);
        if (annotation == null || !isCallingUserAdmin()) {
            String errDesc = (annotation == null) ? String.format("Annotation id:%s does not exist", uuid) :
                    String.format("Type: %s", annotation.getEntityType().name());
            throw new CloudRuntimeException(String.format("Only admins can update annotations' visibility. " +
                    "Cannot update visibility for annotation with id: %s - %s", uuid, errDesc));
        }
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Updating annotation with uuid: %s visibility to %B: ", uuid, adminsOnly));
        }
        annotation.setAdminsOnly(adminsOnly);
        annotationDao.update(annotation.getId(), annotation);
        return createAnnotationResponse(annotation);
    }

    private boolean isCallingUserAllowedToRemoveAnnotation(AnnotationVO annotation) {
        if (annotation == null) {
            return false;
        }
        if (isCallingUserAdmin()) {
            return true;
        }
        UserVO callingUser = getCallingUserFromContext();
        String annotationOwnerUuid = annotation.getUserUuid();
        return annotationOwnerUuid != null && annotationOwnerUuid.equals(callingUser.getUuid());
    }

    private UserVO getCallingUserFromContext() {
        CallContext ctx = CallContext.current();
        long userId = ctx.getCallingUserId();
        UserVO userVO = userDao.findById(userId);
        if (userVO == null) {
            throw new CloudRuntimeException("Cannot find a user with ID " + userId);
        }
        return userVO;
    }

    private boolean isCallingUserAdmin() {
        UserVO userVO = getCallingUserFromContext();
        long accountId = userVO.getAccountId();
        AccountVO accountVO = accountDao.findById(accountId);
        if (accountVO == null) {
            throw new CloudRuntimeException("Cannot find account with ID + " + accountId);
        }
        Long roleId = accountVO.getRoleId();
        Role role = roleService.findRole(roleId);
        if (role == null) {
            throw new CloudRuntimeException("Cannot find role with ID " + roleId);
        }
        return adminRoles.contains(role.getRoleType());
    }

    private Pair<List<AnnotationVO>, Integer> getAnnotationsForApiCmd(ListAnnotationsCmd cmd) {
        List<AnnotationVO> annotations = new ArrayList<>();
        String userUuid = cmd.getUserUuid();
        String entityUuid = cmd.getEntityUuid();
        String entityType = cmd.getEntityType();
        String annotationFilter = isNotBlank(cmd.getAnnotationFilter()) ? cmd.getAnnotationFilter() : "all";
        boolean isCallerAdmin = isCallingUserAdmin();
        if (org.apache.commons.lang3.StringUtils.isAllEmpty(entityUuid, entityType) &&
                !isCallerAdmin && annotationFilter.equalsIgnoreCase("all")) {
            throw new CloudRuntimeException("Only admins can filter all the annotations");
        }
        UserVO callingUser = getCallingUserFromContext();
        String callingUserUuid = callingUser.getUuid();
        String keyword = cmd.getKeyword();

        if (cmd.getUuid() != null) {
            String uuid = cmd.getUuid();
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("getting single annotation by uuid: " + uuid);
            }

            AnnotationVO annotationVO = annotationDao.findByUuid(uuid);
            if (annotationVO != null && annotationVO.getUserUuid().equals(userUuid) &&
                    (annotationFilter.equalsIgnoreCase("all") ||
                    (annotationFilter.equalsIgnoreCase("self") && annotationVO.getUserUuid().equals(callingUserUuid))) &&
                annotationVO.isAdminsOnly() == isCallerAdmin) {
                annotations.add(annotationVO);
            }
        } else if (isNotBlank(entityType)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("getting annotations for type: " + entityType);
            }
            if (isNotBlank(entityUuid)) {
                annotations = getAnnotationsByEntityIdAndType(entityType, entityUuid, userUuid, isCallerAdmin,
                        annotationFilter, callingUserUuid, keyword, callingUser);
            } else {
                if (!isCallerAdmin) {
                    throw new CloudRuntimeException("Only admins can filter all the annotations of a certain entity type");
                }
                annotations = annotationDao.listByEntityType(entityType, userUuid, true,
                        annotationFilter, callingUserUuid, keyword);
            }
        } else if (isNotBlank(entityUuid)) {
            AnnotationVO annotation = annotationDao.findOneByEntityId(entityUuid);
            if (annotation != null) {
                String type = annotation.getEntityType().name();
                annotations = getAnnotationsByEntityIdAndType(type, entityUuid, userUuid, isCallerAdmin,
                        annotationFilter, callingUserUuid, keyword, callingUser);
            }
        } else {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("getting all annotations");
            }
            if ("self".equalsIgnoreCase(annotationFilter) && isBlank(userUuid)) {
                userUuid = callingUserUuid;
            }
            annotations = annotationDao.listAllAnnotations(userUuid, isCallerAdmin, annotationFilter, keyword);
        }
        List<AnnotationVO> paginated = StringUtils.applyPagination(annotations, cmd.getStartIndex(), cmd.getPageSizeVal());
        return (paginated != null) ? new Pair<>(paginated, annotations.size()) :
                new Pair<>(annotations, annotations.size());
    }

    private List<AnnotationVO> getAnnotationsByEntityIdAndType(String entityType, String entityUuid, String userUuid,
                                                               boolean isCallerAdmin, String annotationFilter,
                                                               String callingUserUuid, String keyword, UserVO callingUser) {
        if (!isCallerAdmin) {
            ensureEntityIsOwnedByTheUser(entityType, entityUuid, callingUser);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("getting annotations for entity: " + entityUuid);
        }
        return annotationDao.listByEntity(entityType, entityUuid, userUuid, isCallerAdmin,
                annotationFilter, callingUserUuid, keyword);
    }

    private void ensureEntityIsOwnedByTheUser(String entityType, String entityUuid, UserVO callingUser) {
        try {
            EntityType type = EntityType.valueOf(entityType);
            ControlledEntity entity = getEntityFromUuidAndType(entityUuid, type);
            if (entity == null) {
                String errMsg = String.format("Could not find an entity with type: %s and ID: %s", entityType, entityUuid);
                LOGGER.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
            if (type == EntityType.NETWORK && entity instanceof NetworkVO &&
                    ((NetworkVO) entity).getAclType() == ControlledEntity.ACLType.Domain) {
                NetworkVO network = (NetworkVO) entity;
                DomainVO domain = domainDao.findById(network.getDomainId());
                AccountVO account = accountDao.findById(callingUser.getAccountId());
                accountService.checkAccess(account, domain);
            } else {
                accountService.checkAccess(callingUser, entity);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Could not parse entity type " + entityType, e);
        }
    }

    private ControlledEntity getEntityFromUuidAndType(String entityUuid, EntityType type) {
        switch (type) {
            case VM:
                return vmInstanceDao.findByUuid(entityUuid);
            case VOLUME:
                return volumeDao.findByUuid(entityUuid);
            case SNAPSHOT:
                return snapshotDao.findByUuid(entityUuid);
            case VM_SNAPSHOT:
                return vmSnapshotDao.findByUuid(entityUuid);
            case INSTANCE_GROUP:
                return instanceGroupDao.findByUuid(entityUuid);
            case SSH_KEYPAIR:
                return sshKeyPairDao.findByUuid(entityUuid);
            case NETWORK:
                return networkDao.findByUuid(entityUuid);
            case VPC:
                return vpcDao.findByUuid(entityUuid);
            case PUBLIC_IP_ADDRESS:
                return ipAddressDao.findByUuid(entityUuid);
            case VPN_CUSTOMER_GATEWAY:
                return customerGatewayDao.findByUuid(entityUuid);
            case TEMPLATE:
            case ISO:
                return templateDao.findByUuid(entityUuid);
            case KUBERNETES_CLUSTER:
                return kubernetesClusterHelpers.get(0).findByUuid(entityUuid);
            default:
                throw new CloudRuntimeException("Invalid entity type " + type);
        }
    }

    private List<AnnotationResponse> convertAnnotationsToResponses(List<AnnotationVO> annotations) {
        List<AnnotationResponse> annotationResponses = new ArrayList<>();
        for (AnnotationVO annotation : annotations) {
            annotationResponses.add(createAnnotationResponse(annotation));
        }
        return annotationResponses;
    }

    private ListResponse<AnnotationResponse> createAnnotationsResponseList(List<AnnotationResponse> annotationResponses, Integer count) {
        ListResponse<AnnotationResponse> listResponse = new ListResponse<>();
        listResponse.setResponses(annotationResponses, count);
        return listResponse;
    }

    public AnnotationResponse createAnnotationResponse(AnnotationVO annotation) {
        AnnotationResponse response = new AnnotationResponse();
        response.setUuid(annotation.getUuid());
        response.setEntityType(annotation.getEntityType());
        response.setEntityUuid(annotation.getEntityUuid());
        response.setAnnotation(annotation.getAnnotation());
        response.setUserUuid(annotation.getUserUuid());
        response.setCreated(annotation.getCreated());
        response.setRemoved(annotation.getRemoved());
        UserVO user = userDao.findByUuid(annotation.getUserUuid());
        if (user != null && StringUtils.isNotBlank(user.getUsername())) {
            response.setUsername(user.getUsername());
        }
        setResponseEntityName(response, annotation.getEntityUuid(), annotation.getEntityType());
        response.setAdminsOnly(annotation.isAdminsOnly());
        response.setObjectName("annotation");

        return response;
    }

    private String getInfrastructureEntityName(String entityUuid, EntityType entityType) {
        switch (entityType) {
            case ZONE:
                DataCenterVO zone = dataCenterDao.findByUuid(entityUuid);
                return zone != null ? zone.getName() : null;
            case POD:
                HostPodVO pod = hostPodDao.findByUuid(entityUuid);
                return pod != null ? pod.getName() : null;
            case CLUSTER:
                ClusterVO cluster = clusterDao.findByUuid(entityUuid);
                return cluster != null ? cluster.getName() : null;
            case HOST:
                HostVO host = hostDao.findByUuid(entityUuid);
                return host != null ? host.getName() : null;
            case PRIMARY_STORAGE:
                StoragePoolVO primaryStorage = primaryDataStoreDao.findByUuid(entityUuid);
                return primaryStorage != null ? primaryStorage.getName() : null;
            case SECONDARY_STORAGE:
                ImageStoreVO imageStore = imageStoreDao.findByUuid(entityUuid);
                return imageStore != null ? imageStore.getName() : null;
            case DOMAIN:
                DomainVO domain = domainDao.findByUuid(entityUuid);
                return domain != null ? domain.getName() : null;
            case SERVICE_OFFERING:
                ServiceOfferingVO offering = serviceOfferingDao.findByUuid(entityUuid);
                return offering != null ? offering.getName() : null;
            case DISK_OFFERING:
                DiskOfferingVO diskOffering = diskOfferingDao.findByUuid(entityUuid);
                return diskOffering != null ? diskOffering.getName() : null;
            case NETWORK_OFFERING:
                NetworkOfferingVO networkOffering = networkOfferingDao.findByUuid(entityUuid);
                return networkOffering != null ? networkOffering.getName() : null;
            case VR:
            case SYSTEM_VM:
                VMInstanceVO instance = vmInstanceDao.findByUuid(entityUuid);
                return instance != null ? instance.getInstanceName() : null;
            default:
                return null;
        }
    }

    private void setResponseEntityName(AnnotationResponse response, String entityUuid, EntityType entityType) {
        String entityName = null;
        if (entityType.isUserAllowed()) {
            ControlledEntity entity = getEntityFromUuidAndType(entityUuid, entityType);
            if (entity != null) {
                LOGGER.debug(String.format("Could not find an entity with type: %s and ID: %s", entityType.name(), entityUuid));
                entityName = entity.getName();
            }
        } else {
            entityName = getInfrastructureEntityName(entityUuid, entityType);
        }
        response.setEntityName(entityName);
    }

    @Override public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(AddAnnotationCmd.class);
        cmdList.add(ListAnnotationsCmd.class);
        cmdList.add(RemoveAnnotationCmd.class);
        cmdList.add(UpdateAnnotationVisibilityCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return AnnotationManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{};
    }
}
