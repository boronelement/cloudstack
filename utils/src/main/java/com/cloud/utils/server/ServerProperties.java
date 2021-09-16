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
package com.cloud.utils.server;

import com.cloud.utils.crypt.EncryptionSecretKeyChecker;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.properties.EncryptableProperties;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ServerProperties {
    private static final Logger LOG = Logger.getLogger(ServerProperties.class);

    private static Properties properties = new Properties();
    private static boolean loaded = false;

    public synchronized static Properties getServerProperties(InputStream inputStream) {
        if (!loaded) {
            Properties serverProps = new Properties();
            try {
                serverProps.load(inputStream);

                EncryptionSecretKeyChecker checker = new EncryptionSecretKeyChecker();
                checker.check(serverProps, EncryptionSecretKeyChecker.passwordEncryptionType);

                if (EncryptionSecretKeyChecker.useEncryption()) {
                    StandardPBEStringEncryptor encryptor = EncryptionSecretKeyChecker.getEncryptor();
                    EncryptableProperties encrServerProps = new EncryptableProperties(encryptor);
                    encrServerProps.putAll(serverProps);
                    serverProps = encrServerProps;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load server.properties", e);
            } finally {
                IOUtils.closeQuietly(inputStream);
            }

            properties = serverProps;
            loaded = true;
        }

        return properties;
    }
}
