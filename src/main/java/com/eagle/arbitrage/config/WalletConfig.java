package com.eagle.arbitrage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.io.IOException;
import java.math.BigInteger;

/**
 * 钱包配置类
 */
@Configuration
public class WalletConfig {

    public static long chainId = 56l;
    public static BigInteger gasLimit = new BigInteger("655360");
    public static BigInteger baseGasFeeBNB ;
    public static BigInteger baseGasFeeUSDT;

    public static String contractAddress;

    public static Credentials OPERATOR_CREDENTIALS = Credentials.create("0xab37721f1ffc72f8656b4beea0775198a215e5e3527da5d06512682e68343cbb");

    private static Long OPERATOR_NONCE;

    //初始化nonce
    static {
        try {
            OPERATOR_NONCE = Web3.CLIENT.ethGetTransactionCount(OPERATOR_CREDENTIALS.getAddress(), DefaultBlockParameterName.LATEST).send().getTransactionCount().longValue();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static BigInteger getOperatorNonce() {
        return BigInteger.valueOf(OPERATOR_NONCE++);
    }

    @Value("${contract-address}")
    public void setContractAddress(String contractAddress) {
        WalletConfig.contractAddress = contractAddress;
    }
}
