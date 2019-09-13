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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpRequest;

public class Request {

    final String id;
    final Channel clientChannel;
    final String uri;
    final Object msg;
    volatile Boolean fulfilled = false;

    Request(String id, Channel clientChannel, String uri, Object msg) {
        this.id = id;
        this.clientChannel = clientChannel;
        this.uri = uri;
        this.msg = msg;
    }

    @Override
    public String toString() {
        return id + ", " + clientChannel + ", " + uri + ", " + msg;
    }

    boolean isSSL() {
        return !(msg instanceof DefaultHttpRequest);
    }

    static boolean isRequestValid(Request req) {
        return req != null && req.clientChannel.isActive() && !req.fulfilled;
    }
}
