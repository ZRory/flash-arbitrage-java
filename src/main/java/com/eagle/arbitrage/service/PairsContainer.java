package com.eagle.arbitrage.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.eagle.arbitrage.common.enums.BizCodeEnum;
import com.eagle.arbitrage.common.exception.BizException;
import com.eagle.arbitrage.config.WalletConfig;
import com.eagle.arbitrage.config.Web3;
import com.eagle.arbitrage.contract.Pair;
import com.eagle.arbitrage.entity.Arb;
import com.eagle.arbitrage.entity.PairInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ResourceUtils;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tuples.generated.Tuple3;
import org.web3j.utils.Numeric;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class PairsContainer extends WebSocketClient {

    /**
     * 线程池
     */
    private static ThreadPoolExecutor pairHandlerExecutor = new ThreadPoolExecutor(12, 24, 60, TimeUnit.MINUTES, new ArrayBlockingQueue<>(20000), new ThreadPoolExecutor.DiscardPolicy());
    private static ThreadPoolExecutor pairSyncExecutor = new ThreadPoolExecutor(4, 8, 60, TimeUnit.MINUTES, new ArrayBlockingQueue<>(20000), new ThreadPoolExecutor.DiscardPolicy());

    private static File pairsSerializeFile;

    public static Map<String, PairInfo> PAIRS;

    /**
     * 是否已同步
     */
    public static boolean SYNC = false;

    private String newLogsKey = "";

    private static BigInteger feeUnit = new BigInteger("10000");

    private static BigInteger baseFeeLeft = new BigInteger("9975");

    private static boolean skipNewPair = true;

    private static PairInfo BNB_BUSD_PAIR;


    public PairsContainer(URI serverUri, Draft protocolDraft) {
        super(serverUri, protocolDraft);
    }

    /**
     * 反序列化Pair
     */
    static {
        try {
            pairsSerializeFile = new File("." + File.separator + "pairs.json");
            PAIRS = JSON.parseObject(FileUtils.readFileToString(pairsSerializeFile, StandardCharsets.UTF_8), new TypeReference<ConcurrentHashMap<String, PairInfo>>() {
            });
            BNB_BUSD_PAIR = PAIRS.get("0x16b9a82891338f9ba80e2d6970fdda79d1eb0dae");
        } catch (IOException e) {
            throw new BizException(BizCodeEnum.OPERATION_FAILED, "Pairs持久化文件不存在");
        }
    }

    /**
     * 序列化pair
     *
     * @throws IOException
     */
    @Scheduled(initialDelay = 6000 * 1000L, fixedDelay = 6000 * 1000L)
    public void pairsSerialize() throws IOException {
        log.info("开始持久化Pairs数据");
        FileUtils.writeStringToFile(pairsSerializeFile, JSON.toJSONString(PAIRS), StandardCharsets.UTF_8);
        log.info("持久化Pairs数据完毕");
    }

    /**
     * 更新pair (1.启动时候更新 2.每10分钟完全更新一次)
     */
    @PostConstruct
    @Scheduled(initialDelay = 600 * 1000L, fixedDelay = 600 * 1000L)
    public void pairsSync() {
        new Thread(() -> {
            log.info("开始更新PairsReserves");
            Set<String> keySet = new HashSet<>(PAIRS.keySet());
            CountDownLatch countDownLatch = new CountDownLatch(keySet.size());
            for (String key : keySet) {
                PairInfo pairInfo = PAIRS.get(key);
                pairSyncExecutor.execute(() -> {
                    try {
                        Pair pair = Pair.load(pairInfo.getAddress(), Web3.CLIENT, WalletConfig.OPERATOR_CREDENTIALS, null);
                        Tuple3<BigInteger, BigInteger, BigInteger> reserves = pair.getReserves().send();
                        if (pairInfo.getReserve0().compareTo(reserves.component1()) != 0 || pairInfo.getReserve1().compareTo(reserves.component2()) != 0) {
                            pairInfo.setUpdateTime(LocalDateTime.now());
                            pairInfo.setReserve0(reserves.component1());
                            pairInfo.setReserve1(reserves.component2());
                        }
                    } catch (Exception e) {
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
            }
            SYNC = true;
            log.info("PairsReserves更新完毕");
        }).start();
    }

    /**
     * 订阅logs
     */
    public void subscribeLogs() {
        HashMap<String, Object> request = new HashMap<>();
        List<Object> params = new ArrayList<>();
        params.add("logs");
        HashMap<String, List> topicParams = new HashMap<>();
        //订阅Sync事件
        topicParams.put("topics", Arrays.asList("0x1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1"));
        params.add(topicParams);
        request.put("params", params);
        request.put("id", "eth_subscribe_newLogs");
        request.put("method", "eth_subscribe");
        send(JSON.toJSONString(request));
    }

    @Override
    public void onOpen(ServerHandshake data) {
        subscribeLogs();
    }

    @Override
    public void onMessage(String message) {
        JSONObject result = JSON.parseObject(message);
        if (result.containsKey("id")) {
            if ("eth_subscribe_newLogs".equals(result.get("id"))) {
                newLogsKey = result.getString("result");
            }
        }
        if (result.containsKey("method") && "eth_subscription".equals(result.getString("method"))) {
            JSONObject params = result.getJSONObject("params");
            if (newLogsKey.equals(params.getString("subscription"))) {
                //提交到线程池
                pairHandlerExecutor.execute(() -> handlerNewLogs(params.getJSONObject("result")));
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
    public void handlerNewLogs(JSONObject result) {
        //log.info(result.toString());
        //获取到订阅结果，解析成对象
        Log syncLog = result.toJavaObject(Log.class);
        if ("0xe26e436084348edc0d5c7244903dd2cd2c560f88".equals(syncLog.getAddress()) || "0x96f6eb307dcb0225474adf7ed3af58d079a65ec9".equals(syncLog.getAddress()) || "0xcdaf38ced8bf28ae3a0730dc180703cf794bea59".equals(syncLog.getAddress())) {
            //这个不是pair交易对!
            return;
        }
        //log.info(syncLog.getLogIndex().toString());
        List<BigInteger> reserves = decodeSync(syncLog.getData());
        if (PAIRS.containsKey(syncLog.getAddress())) {
            //如果pair已经存在，则更新reserve信息
            //解析reserve信息
            PairInfo pairInfo = PAIRS.get(syncLog.getAddress());
            pairInfo.setReserve0(reserves.get(0));
            pairInfo.setReserve1(reserves.get(1));
            pairInfo.setUpdateTime(LocalDateTime.now());
        }
    }

    public List<BigInteger> decodeSync(String encodeData) {
        String data = Numeric.cleanHexPrefix(encodeData);
        List<BigInteger> result = new ArrayList<>();
        result.add(Numeric.toBigInt(data.substring(0, 64)));
        result.add(Numeric.toBigInt(data.substring(64)));
        return result;
    }

    /**
     * 计算交易手续费
     */
    private static BigInteger calPairFee(BigInteger amount0In, BigInteger amount1In, BigInteger amount0Out, BigInteger amount1Out, BigInteger reserve0, BigInteger reserve1) {
        BigInteger amount0 = amount0In.subtract(amount0Out);
        reserve0 = reserve0.add(amount0Out).subtract(amount0In);
        reserve1 = reserve1.add(amount1Out).subtract(amount1In);
        if (amount0.compareTo(BigInteger.ZERO) > 0) {
            //amount0是输入
            return feeUnit.subtract(new BigDecimal(reserve0.multiply(amount1Out.abs()).multiply(feeUnit)).divide(new BigDecimal(amount0In.subtract(BigInteger.ONE).multiply(reserve1.subtract(amount1Out.abs()))), 0, RoundingMode.HALF_DOWN).toBigInteger());
        } else {
            return feeUnit.subtract(new BigDecimal(reserve1.multiply(amount0Out.abs()).multiply(feeUnit)).divide(new BigDecimal(amount1In.subtract(BigInteger.ONE).multiply(reserve0.subtract(amount0Out.abs()))), 0, RoundingMode.HALF_DOWN).toBigInteger());
        }
    }


    /**
     * 计算所有可进行的路径
     */
    public static void findArb(String tokenIn, String tokenOut, Integer maxHops, PairInfo targetPair, String targetIn, List<PairInfo> arb, List<Arb> arbs) {
        if (maxHops == 0) {
            return;
        }
        for (PairInfo pair : PAIRS.values()) {
            if (!pair.getToken0().equalsIgnoreCase(tokenIn) && !pair.getToken1().equalsIgnoreCase(tokenIn)) {
                //1.如果这个交易对没有pairIn的信息，认为是不相关交易对
                continue;
            }
            //如果当前pair已经在交易对中出现过则跳过
            if (arb.contains(pair)) {
                continue;
            }
            if (pair.equals(targetPair)) {
                //判断tokenIn是否一致
                if (!tokenIn.equalsIgnoreCase(targetIn)) {
                    //买入卖出方向不一致。。视为无效数据
                    continue;
                }
            }
            //诞生新分支
            ArrayList<PairInfo> newArb = new ArrayList<>(arb);
            newArb.add(pair);
            //计算tokenIn tokenOut分别是什么
            String tempOut = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getToken1() : pair.getToken0();
            if (tempOut.equalsIgnoreCase(tokenOut) && newArb.contains(targetPair)) {
                //加入arbs中
                Arb targetArb = new Arb();
                targetArb.setPairs(newArb);
                targetArb.setToken(tokenOut);
                arbs.add(targetArb);
                return;
            } else {
                //递归继续找
                findArb(tempOut, tokenOut, maxHops - 1, targetPair, targetIn, newArb, arbs);
            }
        }
    }

    //获取bnb等价usdt
    public static BigInteger bnbExchange(BigInteger bnbAmountIn) {
        return getAmountOut("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", bnbAmountIn, BNB_BUSD_PAIR);
    }

    public static BigInteger getAmountOut(String tokenIn, BigInteger amountIn, PairInfo pair) {
        BigInteger reserveIn = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getReserve0() : pair.getReserve1();
        BigInteger reserveOut = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getReserve1() : pair.getReserve0();
        BigInteger amountInWithFee = amountIn.multiply(feeUnit.subtract(pair.getFee()));
        BigInteger numerator = amountInWithFee.multiply(reserveOut);
        BigInteger denominator = reserveIn.multiply(feeUnit).add(amountInWithFee);
        BigInteger amountOut = numerator.divide(denominator);
        return amountOut;
    }

    public static List<BigInteger> calEaEb(String tokenIn, List<PairInfo> pairs) {
        String tokenOut = null;
        BigInteger ea = null;
        BigInteger eb = null;
        for (int i = 0; i < pairs.size(); i++) {
            PairInfo pair = pairs.get(i);
            if (i == 0) {
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getToken1() : pair.getToken0();
            } else if (i == 1) {
                PairInfo oldPair = pairs.get(i - 1);
                BigInteger ra = oldPair.getToken0().equalsIgnoreCase(tokenIn) ? oldPair.getReserve0() : oldPair.getReserve1();
                BigInteger rb = oldPair.getToken0().equalsIgnoreCase(tokenIn) ? oldPair.getReserve1() : oldPair.getReserve0();
                BigInteger rb1 = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve0() : pair.getReserve1();
                BigInteger rc = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve1() : pair.getReserve0();
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getToken1() : pair.getToken0();
                BigInteger denominator = feeUnit.multiply(rb1).add(feeUnit.subtract(pair.getFee()).multiply(rb));
                ea = feeUnit.multiply(ra).multiply(rb1).divide(denominator);
                eb = feeUnit.subtract(pair.getFee()).multiply(rb).multiply(rc).divide(denominator);
            } else {
                BigInteger ra = ea;
                BigInteger rb = eb;
                BigInteger rb1 = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve0() : pair.getReserve1();
                BigInteger rc = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve1() : pair.getReserve0();
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getToken1() : pair.getToken0();
                BigInteger denominator = feeUnit.multiply(rb1).add(feeUnit.subtract(pair.getFee()).multiply(rb));
                ea = feeUnit.multiply(ra).multiply(rb1).divide(denominator);
                eb = feeUnit.subtract(pair.getFee()).multiply(rb).multiply(rc).divide(denominator);
            }
        }
        List<BigInteger> result = new ArrayList<>();
        result.add(ea);
        result.add(eb);
        return result;
    }

    public static BigInteger getOptimalAmount(String tokenIn, List<PairInfo> pairs) {
        List<BigInteger> eaEb = calEaEb(tokenIn, pairs);
        BigInteger ea = eaEb.get(0);
        BigInteger eb = eaEb.get(1);
        return getOptimalAmount(ea, eb);
    }

    public static BigInteger getOptimalAmount(BigInteger ea, BigInteger eb) {
        if (ea.compareTo(eb) > 0) {
            return BigInteger.ZERO;
        }
        BigInteger optionAmount = (ea.multiply(eb).multiply(baseFeeLeft).multiply(feeUnit).sqrt().subtract(ea.multiply(feeUnit))).divide(baseFeeLeft);
        return optionAmount;
    }

    public static List<BigInteger> getAmountsOut(String tokenIn, BigInteger amountIn, List<PairInfo> pairs) {
        List<BigInteger> amountsOut = new ArrayList<>();
        for (PairInfo pair : pairs) {
            BigInteger amountOut = getAmountOut(tokenIn, amountIn, pair);
            amountsOut.add(amountOut);
            tokenIn = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getToken1() : pair.getToken0();
            amountIn = amountOut;
        }
        return amountsOut;
    }


}
