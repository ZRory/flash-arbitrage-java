package com.eagle.arbitrage.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.eagle.arbitrage.config.WalletConfig;
import com.eagle.arbitrage.config.Web3;
import com.eagle.arbitrage.contract.Eagle;
import com.eagle.arbitrage.entity.Arb;
import com.eagle.arbitrage.entity.PairInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.eagle.arbitrage.config.Web3.GAS_PRICE;

@Slf4j
public class TransactionHandler extends WebSocketClient {

    /**
     * 线程池
     */
    private static ThreadPoolExecutor transactionHandlerExecutor = new ThreadPoolExecutor(4, 8, 60, TimeUnit.MINUTES, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());

    private static BigInteger minGas = new BigInteger("100000");

    private String newPendingTransactionsKey = "";

    private static BigInteger feeUnit = new BigInteger("10000");

    public static List<String> targetTokens = new ArrayList<>();

    static {
        //WBNB
        targetTokens.add("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
        //USDC
        targetTokens.add("0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
        //USDT
        targetTokens.add("0x55d398326f99059ff775485246999027b3197955");
        //BUSD
        targetTokens.add("0xe9e7cea3dedca5984780bafc599bd69add087d56");
    }


    public TransactionHandler(URI serverUri, Draft protocolDraft) {
        super(serverUri, protocolDraft);
    }

    /**
     * 订阅PendingTxs
     */
    public void subscribePendingTxs() {
        HashMap<String, Object> param = new HashMap<>();
        param.put("params", Arrays.asList("newPendingTransactions"));
        param.put("id", "eth_subscribe_newPendingTransactions");
        param.put("method", "eth_subscribe");
        send(JSON.toJSONString(param));
    }

    @Override
    public void onOpen(ServerHandshake data) {
        subscribePendingTxs();
    }

    @Override
    public void onMessage(String message) {
        JSONObject result = JSON.parseObject(message);
        if (result.containsKey("id")) {
            if ("eth_subscribe_newPendingTransactions".equals(result.get("id"))) {
                newPendingTransactionsKey = result.getString("result");
            }
        }
        if (result.containsKey("method") && "eth_subscription".equals(result.getString("method"))) {
            JSONObject params = result.getJSONObject("params");
            if (newPendingTransactionsKey.equals(params.getString("subscription"))) {
//                try {
//                    handlerNewTxs(params.getString("result"));
//                } catch (IOException e) {
//                    log.error("", e);
//                }
                //提交到线程池
                transactionHandlerExecutor.execute(() -> {
                    try {
                        handlerNewTxs(params.getString("result"));
                    } catch (IOException e) {
                        log.error("", e);
                    }
                });
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {

    }

    @Override
    public void onError(Exception ex) {

    }

    /**
     * 每30秒检测断开后重连
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void heartBeat() throws InterruptedException {
        if (getReadyState() == READYSTATE.OPEN) {
        } else if (getReadyState() == READYSTATE.NOT_YET_CONNECTED) {
            log.info("未连接重连");
            if (isClosed()) {
                reconnectBlocking();
            } else {
                connectBlocking();
            }
        } else if (getReadyState() == READYSTATE.CLOSED) {
            log.info("已关闭重连");
            reconnectBlocking();
        }
    }

    /**
     * 抓取并更新pair交易对
     */
    public void handlerNewTxs(String transactionHash) throws IOException {
        //没有同步完成 先不处理
        if (!PairsContainer.SYNC) {
            return;
        }
        Optional<Transaction> transactionOptional = Web3.CLIENT.ethGetTransactionByHash(transactionHash).send().getTransaction();
        if (!transactionOptional.isPresent()) {
            return;
        }
        //查询transaction详情
        Transaction transaction = transactionOptional.get();
        if (transaction == null) {
            return;
        }
        if ("0x".equals(transaction.getInput())) {
            return;
        }
        if (transaction.getGasPrice().compareTo(GAS_PRICE) < 0) {
            return;
        }
        if (transaction.getGas().compareTo(minGas) < 0) {
            return;
        }
        if (StringUtils.isEmpty(transaction.getTo())) {
            return;
        }
        if (transaction.getBlockHash() != null) {
            return;
        }
        //        if (transaction.getTo().equalsIgnoreCase("0xca4533591f5e5256f1bdb0f07fee3be76a1aae35")) {
        //            log.info(transactionHash);
        //        } else {
        //            return;
        //        }
        //tx 需要转为request
        org.web3j.protocol.core.methods.request.Transaction traceCallTx = org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(transaction.getFrom(), transaction.getTo(), transaction.getInput());
        long startTime = System.currentTimeMillis();
        JSONObject traceCall = Web3.CLIENT.debugTraceCall(traceCallTx, DefaultBlockParameterName.PENDING).send().getResult();
        if (traceCall.getBoolean("failed")) {
            return;
        }
        JSONArray structLogs = traceCall.getJSONArray("structLogs");
        if (structLogs.size() < 3000) {
            return;
        }
        //log.info("traceCall耗时：{}", System.currentTimeMillis() - startTime);
        //log.info(String.valueOf(structLogs.size()));
        //log.info(transaction.getRaw().toString());
        for (int i = 0; i < structLogs.size(); i++) {
            JSONObject structLog = structLogs.getJSONObject(i);
            JSONArray pairStack = structLog.getJSONArray("stack");
            if (pairStack.size() < 8) {
                continue;
            }
            String functionCode = pairStack.getString(pairStack.size() - 1);
            if (!"0x22c0d9f".equals(functionCode)
                    && !"0x6a627842".equals(functionCode)
                    && !"0x89afcb44".equals(functionCode)) {
                continue;
            }
            //找到目标pairStack;
            //log.info(pairStack.toString());
            String pairAddress = "";
            for (int j = pairStack.size() - 1; j >= 0; j--) {
                String tempStackValue = pairStack.getString(j);
                if (tempStackValue.length() == 42) {
                    pairAddress = tempStackValue;
                    break;
                }
            }
            //log.info("findPair耗时：{}", System.currentTimeMillis() - startTime);
            //沒有這個交易對
            if (!PairsContainer.PAIRS.containsKey(pairAddress)) {
                continue;
            }
            //向下找sync函数
            for (int j = i; j < structLogs.size(); j++) {
                JSONObject syncStructLog = structLogs.getJSONObject(j);
                JSONArray syncStack = syncStructLog.getJSONArray("stack");
                if (syncStack.size() < 10) {
                    continue;
                }
                if (!functionCode.equals(syncStack.getString(0))) {
                    continue;
                }
                if (!"0x1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1".equals(syncStack.getString(syncStack.size() - 1))) {
                    //Sync方法函数
                    continue;
                }
                //找到目标sync方法参数
                //log.info(syncStack.toString());
                //解析reserve信息
                int startIndex = syncStack.indexOf("0xa4");
                if (startIndex == -1) {
                    continue;
                }
                //log.info("findSync耗时：{}", System.currentTimeMillis() - startTime);
                BigInteger reserve0Before = Numeric.toBigInt(syncStack.getString(startIndex + 2));
                BigInteger reserve1Before = Numeric.toBigInt(syncStack.getString(startIndex + 3));
                BigInteger reserve0After = Numeric.toBigInt(syncStack.getString(startIndex + 4));
                BigInteger reserve1After = Numeric.toBigInt(syncStack.getString(startIndex + 5));
                //log.info(reserve0After.toString());
                PairInfo pair = PairsContainer.PAIRS.get(pairAddress);
                //如果是稳定币交易对则放弃
                if (targetTokens.contains(pair.getToken0()) && targetTokens.contains(pair.getToken1())) {
                    continue;
                }
                //获取tokenIn & tokenOut
                String tokenOut = reserve0After.compareTo(reserve0Before) >= 0 ? pair.getToken1() : pair.getToken0();
                //获取最佳交易对
                List<Arb> arbs = new ArrayList<>();
                CountDownLatch countDownLatch = new CountDownLatch(targetTokens.size());
                for (String targetToken : targetTokens) {
                    new Thread(() -> {
                        PairsContainer.findArb(targetToken, targetToken, 2, pair, tokenOut, new ArrayList<>(), arbs);
                        countDownLatch.countDown();
                    }).start();
                }
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    return;
                }
                log.info("findArb耗时：{}", System.currentTimeMillis() - startTime);
                //log.info(JSON.toJSONString(arbs));
                //使用所有交易对计算最优价格
                Arb attackArb = null;
                BigInteger optimalAmount = BigInteger.ZERO;
                BigInteger optimalAmountOut = BigInteger.ZERO;
                BigInteger optimalProfitAmount = BigInteger.ZERO;
                for (Arb arb : arbs) {
                    List<PairInfo> pairs = arb.getPairs();
                    //更新目标reserve信息
                    for (int k = 0; k < pairs.size(); k++) {
                        if (pairs.get(k).getAddress().equals(pairAddress)) {
                            PairInfo pairInfo = new PairInfo();
                            BeanUtils.copyProperties(pairs.get(k), pairInfo);
                            //将更新后reserve赋值
                            pairInfo.setReserve0(reserve0After);
                            pairInfo.setReserve1(reserve1After);
                            pairs.set(k, pairInfo);
                            //log.info(JSON.toJSONString(PairsContainer.PAIRS.get(pairAddress)));
                            break;
                        }
                    }
                    //计算tokenIn
                    //String tokenIn = targetTokens.contains(arb.get(0).getToken0()) ? arb.get(0).getToken0() : arb.get(0).getToken1();
                    BigInteger tempOptimalAmount = PairsContainer.getOptimalAmount(arb.getToken(), pairs);
                    if (tempOptimalAmount.compareTo(BigInteger.ZERO) <= 0) {
                        continue;
                    }
                    List<BigInteger> amountsOut = PairsContainer.getAmountsOut(arb.getToken(), tempOptimalAmount, pairs);
                    BigInteger tempOptimalAmountOut = amountsOut.get(amountsOut.size() - 1);
                    //计算盈利金额
                    BigInteger tempProfitAmount;
                    if ("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c".equals(arb.getToken())) {
                        //BNB的话需要转换成usdt
                        tempProfitAmount = PairsContainer.bnbExchange(tempOptimalAmountOut.subtract(tempOptimalAmount));
                    } else {
                        tempProfitAmount = tempOptimalAmountOut.subtract(tempOptimalAmount);
                    }
                    if (tempProfitAmount.compareTo(optimalProfitAmount) > 0) {
                        attackArb = arb;
                        optimalAmount = tempOptimalAmount;
                        optimalAmountOut = tempOptimalAmountOut;
                        optimalProfitAmount = tempProfitAmount;
                    }

                }
                if (optimalProfitAmount.compareTo(BigInteger.ZERO) > 0) {
                    //log.info("查找入侵交易对耗时：{}", System.currentTimeMillis() - startTime);
                    //计算amountOutMin
                    BigInteger amountOutMin = optimalAmount;
                    if (attackArb.getToken().equals("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c")) {
                        //BNB
                        amountOutMin = amountOutMin.add(WalletConfig.baseGasFeeBNB);
                        return;
                    } else {
                        amountOutMin = amountOutMin.add(WalletConfig.baseGasFeeUSDT);
                    }
                    if (optimalAmount.compareTo(new BigInteger("360000000000000000000")) > 0) {
                        return;
                    }
                    if (optimalAmountOut.compareTo(amountOutMin) >= 0) {
                        //拼裝下單參數
                        Function huntingFunction = Eagle.hunting_v1Function(optimalAmount, amountOutMin, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                        //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                        String data = FunctionEncoder.encode(huntingFunction);
                        RawTransaction rawTransaction = RawTransaction.createTransaction(
                                WalletConfig.getOperatorNonce(),
                                transaction.getGasPrice(),
                                WalletConfig.gasLimit,
                                WalletConfig.contractAddress, data);
                        //交易簽名
                        String encodeTx = Numeric.toHexString(TransactionEncoder.signMessage(rawTransaction, WalletConfig.chainId, WalletConfig.OPERATOR_CREDENTIALS));
                        //發送交易
                        EthSendTransaction huntingResult = Web3.CLIENT.ethSendRawTransaction(encodeTx).send();
                        log.info("targetHash:{},sendSuccessHash:{}", transactionHash, huntingResult.getTransactionHash());
//                        //GAS费计算，下单逻辑
//                        Function huntingEstimateGasFunction = Eagle.hunting_v1Function(optimalAmount, BigInteger.ZERO, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                        //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                        String estimateGasData = FunctionEncoder.encode(huntingEstimateGasFunction);
//                        org.web3j.protocol.core.methods.request.Transaction huntingEsGasTx = org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(WalletConfig.OPERATOR_CREDENTIALS.getAddress(), WalletConfig.contractAddress, estimateGasData);
//                        EthEstimateGas result = Web3.CLIENT.ethEstimateGas(huntingEsGasTx).send();
//                        Response.Error error = result.getError();
//                        if (ObjectUtils.isNotEmpty(error)) {
//                            log.info("EstimateGasError:{}", JSON.toJSONString(error));
//                            log.info("EstimateGas耗时：{}", System.currentTimeMillis() - startTime);
//                            return;
//                        }
//                        BigInteger gasUsed = result.getAmountUsed();
//                        //需要x2 因为SELF DESTRUCT 会节省一半GAS费
//                        BigInteger gasFee = gasUsed.multiply(transaction.getGasPrice()).multiply(BigInteger.TWO);
//                        //gasFee 转为USDT
//                        gasFee = PairsContainer.bnbExchange(gasFee);
//                        //判断是否覆盖盈利
//                        if (optimalProfitAmount.compareTo(gasFee) > 0) {
//                            //盈利金额大于gas费
//                            //拼裝下單參數
//                            Function huntingFunction = Eagle.hunting_v1Function(optimalAmount, optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                            //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                            String data = FunctionEncoder.encode(huntingFunction);
//                            RawTransaction rawTransaction = RawTransaction.createTransaction(
//                                    WalletConfig.getOperatorNonce(),
//                                    transaction.getGasPrice(),
//                                    WalletConfig.gasLimit,
//                                    WalletConfig.contractAddress, data);
//                            //交易簽名
//                            String encodeTx = Numeric.toHexString(TransactionEncoder.signMessage(rawTransaction, WalletConfig.chainId, WalletConfig.OPERATOR_CREDENTIALS));
//                            //發送交易
//                            EthSendTransaction huntingResult = Web3.CLIENT.ethSendRawTransaction(encodeTx).send();
//                            log.info("targetHash:{},sendSuccessHash:{}", transactionHash, huntingResult.getTransactionHash());
//                        }
                    }
                }
                i = j;
                break;
            }
        }
    }

}
