/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.relay;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ConfigPropertyLoader {
    private static Properties prop;
    private static String file;

    private static void init() throws IOException {
        if (prop != null) {
            return;
        }
        prop = new Properties();
        //load a properties file from class path, inside static method
        file = System.getProperty("relay.config.properties");
        if (file == null) {
            throw new RuntimeException("The JVM option shall be defined: 'relay.config.properties'.");
        }
        InputStream is = new FileInputStream(new File(file));
        prop.load(is);
    }

    static String getProperty(String key, String def) {
        try {
            init();
            String s = prop.getProperty(key);
            if (s == null) {
                s = def;
            }
            return s;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    static String getProperty(String key) {
        return getProperty(key, null);
    }

    static String getPropertyNotNull(String key) {
        String s = getProperty(key);
        if (s == null) {
            throw new RuntimeException("The config property " + key + " is not defined in config file " + file);
        }
        return s;
    }
}
