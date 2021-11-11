/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.lwm2m.bootstrap.store;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.SecurityMode;
import org.eclipse.leshan.server.bootstrap.BootstrapConfig;
import org.eclipse.leshan.server.bootstrap.EditableBootstrapConfigStore;
import org.eclipse.leshan.server.bootstrap.InvalidConfigurationException;
import org.eclipse.leshan.server.security.BootstrapSecurityStore;
import org.eclipse.leshan.server.security.SecurityInfo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MSecurityMode;
import org.thingsboard.server.common.data.device.profile.lwm2m.bootstrap.AbstractLwM2MBootstrapServerCredential;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.lwm2m.bootstrap.secure.LwM2MBootstrapConfig;
import org.thingsboard.server.transport.lwm2m.secure.LwM2mCredentialsSecurityInfoValidator;
import org.thingsboard.server.transport.lwm2m.secure.TbLwM2MSecurityInfo;
import org.thingsboard.server.transport.lwm2m.server.LwM2mSessionMsgListener;
import org.thingsboard.server.transport.lwm2m.server.LwM2mTransportContext;
import org.thingsboard.server.transport.lwm2m.server.LwM2mTransportServerHelper;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.thingsboard.server.transport.lwm2m.server.uplink.LwM2mTypeServer.BOOTSTRAP;
import static org.thingsboard.server.transport.lwm2m.utils.LwM2MTransportUtil.LOG_LWM2M_ERROR;
import static org.thingsboard.server.transport.lwm2m.utils.LwM2MTransportUtil.LOG_LWM2M_INFO;
import static org.thingsboard.server.transport.lwm2m.utils.LwM2MTransportUtil.LOG_LWM2M_TELEMETRY;

@Slf4j
@Service("LwM2MBootstrapSecurityStore")
@ConditionalOnExpression("('${service.type:null}'=='tb-transport' && '${transport.lwm2m.enabled:false}'=='true' && '${transport.lwm2m.bootstrap.enable:false}'=='true') || ('${service.type:null}'=='monolith' && '${transport.lwm2m.enabled}'=='true' && '${transport.lwm2m.bootstrap.enable}'=='true')")
public class LwM2MBootstrapSecurityStore implements BootstrapSecurityStore {

    private final EditableBootstrapConfigStore bootstrapConfigStore;

    private final LwM2mCredentialsSecurityInfoValidator lwM2MCredentialsSecurityInfoValidator;

    private final LwM2mTransportContext context;
    private final LwM2mTransportServerHelper helper;
    private final Map<String /* endpoint */, TransportProtos.SessionInfoProto> bsSessions = new ConcurrentHashMap<>();

    public LwM2MBootstrapSecurityStore(EditableBootstrapConfigStore bootstrapConfigStore, LwM2mCredentialsSecurityInfoValidator lwM2MCredentialsSecurityInfoValidator, LwM2mTransportContext context, LwM2mTransportServerHelper helper) {
        this.bootstrapConfigStore = bootstrapConfigStore;
        this.lwM2MCredentialsSecurityInfoValidator = lwM2MCredentialsSecurityInfoValidator;
        this.context = context;
        this.helper = helper;
    }

    @Override
    public Iterator<SecurityInfo> getAllByEndpoint(String endPoint) {
        // TODO
        TbLwM2MSecurityInfo store = lwM2MCredentialsSecurityInfoValidator.getEndpointSecurityInfoByCredentialsId(endPoint, BOOTSTRAP);
        if (store.getBootstrapCredentialConfig() != null) {
            /* add value to store  from BootstrapJson */
            this.setBootstrapConfigScurityInfo(store);
            BootstrapConfig bsConfigNew = store.getBootstrapConfig();
            if (bsConfigNew != null) {
                try {
                    for (String config : bootstrapConfigStore.getAll().keySet()) {
                        if (config.equals(endPoint)) {
                            bootstrapConfigStore.remove(config);
                        }
                    }
                    bootstrapConfigStore.add(endPoint, bsConfigNew);
                } catch (InvalidConfigurationException e) {
                    if (e.getMessage().contains("Psk identity") && e.getMessage().contains("already used for this bootstrap server")) {
                        log.trace("Invalid Bootstrap Configuration", e);
                    }
                    else {
                        log.error("Invalid Bootstrap Configuration", e);
                    }
                }
                return store.getSecurityInfo() == null ? null : Collections.singletonList(store.getSecurityInfo()).iterator();
            }
        }
        return null;
    }

    @Override
    public SecurityInfo getByIdentity(String identity) {
        TbLwM2MSecurityInfo store = lwM2MCredentialsSecurityInfoValidator.getEndpointSecurityInfoByCredentialsId(identity, BOOTSTRAP);
        if (store.getBootstrapCredentialConfig() != null && store.getSecurityMode() != null) {
            /* add value to store  from BootstrapJson */
            this.setBootstrapConfigScurityInfo(store);
            BootstrapConfig bsConfig = store.getBootstrapConfig();
            if (bsConfig.security != null) {
                try {
                    bootstrapConfigStore.add(store.getEndpoint(), bsConfig);
                } catch (InvalidConfigurationException e) {
                    log.error("Invalid Bootstrap Configuration", e);
                }
                return store.getSecurityInfo();
            }
        }
        return null;
    }

    private void setBootstrapConfigScurityInfo(TbLwM2MSecurityInfo store) {
        /* BootstrapConfig */
        LwM2MBootstrapConfig lwM2MBootstrapConfig = this.getParametersBootstrap(store);
        if (lwM2MBootstrapConfig != null) {
            /* Security info */
//            switch (lwM2MBootstrapConfig.getBootstrapServer().getSecurityMode()) {
//                /* Use RPK only */
//                case PSK:
//                    store.setSecurityInfo(SecurityInfo.newPreSharedKeyInfo(store.getEndpoint(),
//                            lwM2MBootstrapConfig.getBootstrapServer().getClientPublicKeyOrId(),
//                            Hex.decodeHex(lwM2MBootstrapConfig.getBootstrapServer().getClientSecretKey().toCharArray())));
//                    store.setSecurityMode(SecurityMode.PSK);
//                    break;
//                case RPK:
//                    try {
////                        store.setSecurityInfo(SecurityInfo.newRawPublicKeyInfo(store.getEndpoint(),
////                                SecurityUtil.publicKey.decode(Hex.decodeHex(lwM2MBootstrapConfig.getBootstrapServer().getClientPublicKeyOrId().toCharArray()))));
////                        store.setSecurityMode(SecurityMode.RPK);
//                        break;
//                    } catch (IOException | GeneralSecurityException e) {
//                        log.error("Unable to decode Client public key for [{}]  [{}]", store.getEndpoint(), e.getMessage());
//                    }
//                case X509:
//                    store.setSecurityInfo(SecurityInfo.newX509CertInfo(store.getEndpoint()));
//                    store.setSecurityMode(SecurityMode.X509);
//                    break;
//                case NO_SEC:
//                    store.setSecurityMode(SecurityMode.NO_SEC);
//                    store.setSecurityInfo(null);
//                    break;
//                default:
//            }
            BootstrapConfig bootstrapConfig = lwM2MBootstrapConfig.getLwM2MBootstrapConfig();
            store.setBootstrapConfig(bootstrapConfig);
        }
    }

    private LwM2MBootstrapConfig getParametersBootstrap(TbLwM2MSecurityInfo store) {
        LwM2MBootstrapConfig lwM2MBootstrapConfig = store.getBootstrapCredentialConfig();
        if (lwM2MBootstrapConfig != null) {
//            LwM2MBootstrapServersConfiguration bootstrapObject = getBootstrapParametersFromThingsboard(store.getDeviceProfile());
//            lwM2MBootstrapConfig.setServers(JacksonUtil.fromString(JacksonUtil.toString(bootstrapObject.getServers()), LwM2MBootstrapServers.class));
//            LwM2MServerBootstrap bootstrapServerProfile = JacksonUtil.fromString(JacksonUtil.toString(bootstrapObject.getBootstrapServer()), LwM2MServerBootstrap.class);
//            if (SecurityMode.NO_SEC != bootstrapServerProfile.getSecurityMode() && bootstrapServerProfile != null) {
//                bootstrapServerProfile.setSecurityHost(bootstrapServerProfile.getHost());
//                bootstrapServerProfile.setSecurityPort(bootstrapServerProfile.getPort());
//            }
//            LwM2MServerBootstrap profileLwm2mServer = JacksonUtil.fromString(JacksonUtil.toString(bootstrapObject.getLwm2mServer()), LwM2MServerBootstrap.class);
//            if (SecurityMode.NO_SEC != profileLwm2mServer.getSecurityMode() && profileLwm2mServer != null) {
//                profileLwm2mServer.setSecurityHost(profileLwm2mServer.getHost());
//                profileLwm2mServer.setSecurityPort(profileLwm2mServer.getPort());
//            }


            UUID sessionUUiD = UUID.randomUUID();
            TransportProtos.SessionInfoProto sessionInfo = helper.getValidateSessionInfo(store.getMsg(), sessionUUiD.getMostSignificantBits(), sessionUUiD.getLeastSignificantBits());
            bsSessions.put(store.getEndpoint(), sessionInfo);
            context.getTransportService().registerAsyncSession(sessionInfo, new LwM2mSessionMsgListener(null, null, null, sessionInfo, context.getTransportService()));
            if (this.getValidatedSecurityMode(lwM2MBootstrapConfig)) {
//                lwM2MBootstrapConfig.setBootstrapServer(new LwM2MServerBootstrap(lwM2MBootstrapConfig.getBootstrapServer(), bootstrapServerProfile));
//                lwM2MBootstrapConfig.setLwm2mServer(new LwM2MServerBootstrap(lwM2MBootstrapConfig.getLwm2mServer(), profileLwm2mServer));
                String logMsg = String.format("%s: getParametersBootstrap: %s Access connect client with bootstrap server.", LOG_LWM2M_INFO, store.getEndpoint());
                helper.sendParametersOnThingsboardTelemetry(helper.getKvStringtoThingsboard(LOG_LWM2M_TELEMETRY, logMsg), sessionInfo);
                return lwM2MBootstrapConfig;
            } else {
                log.error(" [{}] Different values SecurityMode between of client and profile.", store.getEndpoint());
                log.error("{} getParametersBootstrap: [{}] Different values SecurityMode between of client and profile.", LOG_LWM2M_ERROR, store.getEndpoint());
                String logMsg = String.format("%s: getParametersBootstrap: %s Different values SecurityMode between of client and profile.", LOG_LWM2M_ERROR, store.getEndpoint());
                helper.sendParametersOnThingsboardTelemetry(helper.getKvStringtoThingsboard(LOG_LWM2M_TELEMETRY, logMsg), sessionInfo);
                return null;
            }
        }
        log.error("Unable to decode Json or Certificate for [{}]", store.getEndpoint());
        return null;
    }

    /**
     * Bootstrap security have to sync between (bootstrapServer in credential and  bootstrapServer in profile)
     * and (lwm2mServer  in credential and lwm2mServer  in profile
     *
     * @return false if not sync between SecurityMode of Bootstrap credential and profile
     */
//    private boolean getValidatedSecurityMode(LwM2MServerBootstrap bootstrapFromCredential, LwM2MServerBootstrap bootstrapServerProfile, LwM2MServerBootstrap lwm2mFromCredential, LwM2MServerBootstrap profileLwm2mServer) {
    private boolean getValidatedSecurityMode(LwM2MBootstrapConfig lwM2MBootstrapConfig) {
        LwM2MSecurityMode bootstrapServerSecurityMode = lwM2MBootstrapConfig.getBootstrapServer().getSecurityMode();
        LwM2MSecurityMode lwm2mServerSecurityMode = lwM2MBootstrapConfig.getLwm2mServer().getSecurityMode();
        AtomicBoolean validBs = new AtomicBoolean(true);
        AtomicBoolean  validLw = new AtomicBoolean(true);
        lwM2MBootstrapConfig.getServerConfiguration().forEach(serverCredential -> {
            if (((AbstractLwM2MBootstrapServerCredential)serverCredential).isBootstrapServerIs()) {
                if (!bootstrapServerSecurityMode.equals(serverCredential.getSecurityMode())) {
                    validBs.set(false);
                }
            }
            else {
                if (!lwm2mServerSecurityMode.equals(serverCredential.getSecurityMode())) {
                    validLw.set(false);
                }
            }
        });
        return validBs.get()&validLw.get();
    }

    public TransportProtos.SessionInfoProto getSessionByEndpoint(String endpoint) {
        return bsSessions.get(endpoint);
    }

    public TransportProtos.SessionInfoProto removeSessionByEndpoint(String endpoint) {
        return bsSessions.remove(endpoint);
    }
}