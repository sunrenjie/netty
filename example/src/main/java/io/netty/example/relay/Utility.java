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

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.internal.tcnative.SSL;
import io.netty.internal.tcnative.SSLContext;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSslClientContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import java.lang.reflect.Field;

public class Utility {
    private static SslContext sslCtx;
    static {
        // TODO clean up.
        SslProvider provider = SslProvider.JDK;
        try {
            sslCtx = SslContextBuilder.forClient().protocols("TLSv1.2")
                    .sslProvider(provider)
                    // NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                    // Please refer to the HTTP/2 specification for cipher requirements.
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            // ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
            if (sslCtx instanceof OpenSslClientContext) {
                OpenSslClientContext context = (OpenSslClientContext) sslCtx;
                Field f = sslCtx.getClass().getSuperclass().getSuperclass().getDeclaredField("ctx");
                f.setAccessible(true);
                long ctx = (Long) f.get(context);
                SSLContext.clearOptions(ctx, SSL.SSL_OP_NO_TICKET);
                int options = SSLContext.getOptions(ctx);
            }
            sslCtx = SslContextBuilder.forClient().protocols("TLSv1.2").sslProvider(provider).
                    sslContextProvider(new BouncyCastleJsseProvider()).build();
        } catch (Exception e) {
            System.out.println("Cannot create the SslContext sslCtx");
            e.printStackTrace();
        }
    }

    static SslContext clientSslContext() throws Exception {
        return sslCtx;
    }

    public static String[] trimStrings(String[] ss) {
        for (int i = 0; i < ss.length; i++) {
            ss[i] = ss[i].trim();
        }
        return ss;
    }
}
