package com.eagle.arbitrage.config;

import com.eagle.arbitrage.entity.DebugTraceCall;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.JsonRpc2_0Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.utils.Async;

import java.util.Arrays;
import java.util.Collections;

public class Web3Client extends JsonRpc2_0Web3j {

    public static final int DEFAULT_BLOCK_TIME = 3 * 1000;

    public Web3Client(Web3jService web3jService) {
        super(web3jService, DEFAULT_BLOCK_TIME, Async.defaultExecutorService());
    }

    public Request<?, DebugTraceCall> debugTraceCall(Transaction transaction, DefaultBlockParameter defaultBlockParameter) {
        return new Request<>(
                "debug_traceCall",
                Arrays.asList(transaction, defaultBlockParameter),
                web3jService,
                DebugTraceCall.class);
    }


}
