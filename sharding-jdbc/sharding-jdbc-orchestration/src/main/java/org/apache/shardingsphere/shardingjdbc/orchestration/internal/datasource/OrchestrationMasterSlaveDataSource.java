/*
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

package org.apache.shardingsphere.shardingjdbc.orchestration.internal.datasource;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.eventbus.Subscribe;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.shardingsphere.api.config.RuleConfiguration;
import org.apache.shardingsphere.api.config.masterslave.MasterSlaveRuleConfiguration;
import org.apache.shardingsphere.core.constant.ShardingConstant;
import org.apache.shardingsphere.core.rule.MasterSlaveRule;
import org.apache.shardingsphere.orchestration.config.OrchestrationConfiguration;
import org.apache.shardingsphere.orchestration.internal.registry.ShardingOrchestrationFacade;
import org.apache.shardingsphere.orchestration.internal.registry.config.event.DataSourceChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.config.event.MasterSlaveRuleChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.config.event.PropertiesChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.config.service.ConfigurationService;
import org.apache.shardingsphere.orchestration.internal.registry.state.event.DisabledStateChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.state.schema.OrchestrationShardingSchema;
import org.apache.shardingsphere.orchestration.internal.rule.OrchestrationMasterSlaveRule;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.MasterSlaveDataSource;
import org.apache.shardingsphere.shardingjdbc.orchestration.internal.util.DataSourceConverter;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Orchestration master-slave datasource.
 *
 * @author panjuan
 */
@Getter(AccessLevel.PROTECTED)
public class OrchestrationMasterSlaveDataSource extends AbstractOrchestrationDataSource {
    
    private MasterSlaveDataSource dataSource;
    
    public OrchestrationMasterSlaveDataSource(final OrchestrationConfiguration orchestrationConfig) throws SQLException {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(ShardingConstant.LOGIC_SCHEMA_NAME)));
        ConfigurationService configService = getShardingOrchestrationFacade().getConfigService();
        MasterSlaveRuleConfiguration masterSlaveRuleConfig = configService.loadMasterSlaveRuleConfiguration(ShardingConstant.LOGIC_SCHEMA_NAME);
        Preconditions.checkState(!Strings.isNullOrEmpty(masterSlaveRuleConfig.getMasterDataSourceName()), "No available master slave rule configuration to load.");
        dataSource = new MasterSlaveDataSource(DataSourceConverter.getDataSourceMap(configService.loadDataSourceConfigurations(ShardingConstant.LOGIC_SCHEMA_NAME)),
                new OrchestrationMasterSlaveRule(masterSlaveRuleConfig), configService.loadProperties());
        getShardingOrchestrationFacade().init();
    }
    
    public OrchestrationMasterSlaveDataSource(final MasterSlaveDataSource masterSlaveDataSource, final OrchestrationConfiguration orchestrationConfig) throws SQLException {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(ShardingConstant.LOGIC_SCHEMA_NAME)));
        dataSource = new MasterSlaveDataSource(masterSlaveDataSource.getDataSourceMap(),
                new OrchestrationMasterSlaveRule(masterSlaveDataSource.getMasterSlaveRule().getMasterSlaveRuleConfiguration()), masterSlaveDataSource.getShardingProperties().getProps());
        getShardingOrchestrationFacade().init(Collections.singletonMap(ShardingConstant.LOGIC_SCHEMA_NAME, DataSourceConverter.getDataSourceConfigurationMap(dataSource.getDataSourceMap())),
                getRuleConfigurationMap(), null, dataSource.getShardingProperties().getProps());
    }
    
    private Map<String, RuleConfiguration> getRuleConfigurationMap() {
        MasterSlaveRule masterSlaveRule = dataSource.getMasterSlaveRule();
        Map<String, RuleConfiguration> result = new HashMap<>();
        result.put(ShardingConstant.LOGIC_SCHEMA_NAME, new MasterSlaveRuleConfiguration(
                masterSlaveRule.getName(), masterSlaveRule.getMasterDataSourceName(), masterSlaveRule.getSlaveDataSourceNames(), masterSlaveRule.getLoadBalanceAlgorithm()));
        return result;
    }
    
    /**
     * Renew master-slave rule.
     *
     * @param masterSlaveRuleChangedEvent master-slave configuration changed event
     */
    @Subscribe
    @SneakyThrows
    public final synchronized void renew(final MasterSlaveRuleChangedEvent masterSlaveRuleChangedEvent) {
        dataSource = new MasterSlaveDataSource(
                dataSource.getDataSourceMap(), new OrchestrationMasterSlaveRule(masterSlaveRuleChangedEvent.getMasterSlaveRuleConfiguration()), dataSource.getShardingProperties().getProps());
    }
    
    /**
     * Renew master-slave data source.
     *
     * @param dataSourceChangedEvent data source changed event
     */
    @Subscribe
    @SneakyThrows
    public final synchronized void renew(final DataSourceChangedEvent dataSourceChangedEvent) {
        dataSource.close();
        dataSource = new MasterSlaveDataSource(
                DataSourceConverter.getDataSourceMap(dataSourceChangedEvent.getDataSourceConfigurations()), dataSource.getMasterSlaveRule(), dataSource.getShardingProperties().getProps());
    }
    
    /**
     * Renew properties.
     *
     * @param propertiesChangedEvent properties changed event
     */
    @SneakyThrows
    @Subscribe
    public final synchronized void renew(final PropertiesChangedEvent propertiesChangedEvent) {
        dataSource = new MasterSlaveDataSource(dataSource.getDataSourceMap(), dataSource.getMasterSlaveRule(), propertiesChangedEvent.getProps());
    }
    
    /**
     * Renew disabled data source names.
     *
     * @param disabledStateChangedEvent disabled state changed event
     */
    @Subscribe
    public synchronized void renew(final DisabledStateChangedEvent disabledStateChangedEvent) {
        OrchestrationShardingSchema shardingSchema = disabledStateChangedEvent.getShardingSchema();
        if (ShardingConstant.LOGIC_SCHEMA_NAME.equals(shardingSchema.getSchemaName())) {
            ((OrchestrationMasterSlaveRule) dataSource.getMasterSlaveRule()).updateDisabledDataSourceNames(shardingSchema.getDataSourceName(), disabledStateChangedEvent.isDisabled());
        }
    }
}
