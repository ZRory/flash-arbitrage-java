package com.eagle.arbitrage.config;

import com.eagle.arbitrage.service.PairsContainer;
import com.eagle.arbitrage.service.TransactionHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URISyntaxException;

@Configuration
public class Init {

    @Value("${web3.ws.url}")
    private String web3WsUrl;

    @Bean
    public WebSocketClient initPairsContainer() throws URISyntaxException {
        try {
            WebSocketClient webSocketClient = new PairsContainer(new URI(web3WsUrl), new Draft_6455());
            webSocketClient.connect();
            return webSocketClient;
        } catch (Exception e) {
            throw e;
        }
    }

    @Bean
    public WebSocketClient initTransactionHandler() throws URISyntaxException {
        try {
            WebSocketClient webSocketClient = new TransactionHandler(new URI(web3WsUrl), new Draft_6455());
            webSocketClient.connect();
            return webSocketClient;
        } catch (Exception e) {
            throw e;
        }
    }

}
