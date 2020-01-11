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

public final class Server {
    private final String host;
    private final int port;

    public Server(String host, Integer port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return this.host;
    }

    public Integer getPort() {
        return this.port;
    }

    @Override
    public String toString() {
        return this.host + ":" + this.port;
    }

    public static String dumpServers(Server[] servers) {
        if (servers.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append(servers[0].toString());
        for (int i = 1; i < servers.length; i++) {
            sb.append(",");
            sb.append(servers[i].toString());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String[] trimStrings(String[] ss) {
        for (int i = 0; i < ss.length; i++) {
            ss[i] = ss[i].trim();
        }
        return ss;
    }

    public static Server[] loadServers(String csv, int defaultPort) {
        if (csv == null || csv.length() == 0) {
            return null;
        }
        String[] ss = trimStrings(csv.split(","));
        Server[] ret = new Server[ss.length];
        for (int i = 0; i < ss.length; i++) {
            String[] hp = trimStrings(ss[i].split(":"));
            String s = hp[0];
            int port = hp.length == 1 ? defaultPort : Integer.parseInt(hp[1]);
            ret[i] = new Server(s, port);
        }
        return ret;
    }
}
