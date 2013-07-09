/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.cloud;

import java.util.List;

import javax.ejb.Local;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.cloud.StorageNode;
import org.rhq.core.domain.cloud.StorageNodeLoadComposite;
import org.rhq.core.domain.criteria.StorageNodeCriteria;
import org.rhq.core.domain.resource.Resource;
import org.rhq.core.domain.resource.group.ResourceGroup;
import org.rhq.core.domain.util.PageList;

@Local
public interface StorageNodeManagerLocal {

    // The following have package visibility to make accessible to StorageNodeManagerBeanTest
    String STORAGE_NODE_GROUP_NAME = "RHQ Storage Nodes";
    String STORAGE_NODE_RESOURCE_TYPE_NAME = "RHQ Storage Node";
    String STORAGE_NODE_PLUGIN_NAME = "RHQStorage";

    List<StorageNode> getStorageNodes();

    void linkResource(Resource resource);

    /**
     * <p>Returns the summary of load of the storage node.</p>
     *
     * <p>the subject needs to have <code>MANAGE_SETTINGS</code> permissions.</p>
     *
     * @param subject   user that must have proper permissions
     * @param node      storage node entity (it can be a new object, but the id should be set properly)
     * @param beginTime the start time
     * @param endTime   the end time
     * @return instance of {@link StorageNodeLoadComposite} with the aggregate measurement data of selected metrics
     */
    StorageNodeLoadComposite getLoad(Subject subject, StorageNode node, long beginTime, long endTime);

    /**
     * Fetches the list of StorageNode entities based on provided criteria.
     *
     * the subject needs to have MANAGE_SETTINGS permissions.
     *
     * @param subject caller
     * @param criteria the criteria
     * @return list of nodes
     */
    PageList<StorageNode> findStorageNodesByCriteria(Subject subject, StorageNodeCriteria criteria);
    
    /**
     * <p>Prepares the node for subsequent upgrade.</p>
     * <p> CAUTION: this method will set the RHQ server to maintenance mode, RHQ storage flushes all the data to disk
     * and backup of all the keyspaces is created</p>
     * <p>the subject needs to have <code>MANAGE_SETTINGS</code> and <code>MANAGE_INVENTORY</code> permissions.</p>
     * 
     * @param subject caller
     * @param storageNode storage node on which the prepareForUpgrade operation should be run
     */
    void prepareNodeForUpgrade(Subject subject, StorageNode storageNode);

    /**
     * <p>
     * Schedules read repair to run on the storage cluster. The repair operation is executed one node at a time. This
     * method is invoked from {@link org.rhq.enterprise.server.scheduler.jobs.StorageClusterReadRepairJob StorageClusterReadRepairJob}
     * as part of regularly scheduled maintenance.
     * </p>
     * <p>
     * <strong>NOTE:</strong> Repair is one of the most resource-intensive operations that a storage node performs. Make
     * sure you know what you are doing if you invoke this method outside of the regularly scheduled maintenance window.
     * </p>
     */
    void runReadRepair();

    /**
     * Creates the storage node resource group which will be named {@link #STORAGE_NODE_GROUP_NAME}. This method should
     * only be called at start up by {@link org.rhq.enterprise.server.storage.StorageClientManagerBean StorageClientManagerBean}.
     * Storage node entities created during installation will be added to the group.
     */
    void createStorageNodeGroup();

    /**
     * Checks whether or not the storage node resource group exists. This method is very similar to
     * {@link #getStorageNodeGroup()} but may be called prior to the group being created.
     *
     * @return true if the storage node resource group exists, false otherwise.
     */
    boolean storageNodeGroupExists();

    /**
     * This method assumes the storage node resource group already exists; as such, it should only be called from places
     * in the code that are after the point(s) where the group has been created.
     *
     * @return The storage node resource group.
     * @throws IllegalStateException if the group is not found or does not exist.
     */
    ResourceGroup getStorageNodeGroup();

}