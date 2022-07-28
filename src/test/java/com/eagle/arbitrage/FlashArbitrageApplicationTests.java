package com.eagle.arbitrage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.codegen.TruffleJsonFunctionWrapperGenerator;

@SpringBootTest
class FlashArbitrageApplicationTests {

    @Test
    void contextLoads() {
    }

    public static void main(String[] args) throws Exception {
        String[] cmdLine = {"D:\\JavaCodes\\flash-arbitrage\\src\\main\\resources\\contracts\\Eagle.json", "-o", "D:\\JavaCodes\\flash-arbitrage\\src\\main\\resources", "-p", "arbitrage"};
        TruffleJsonFunctionWrapperGenerator.main(cmdLine);
    }

}
