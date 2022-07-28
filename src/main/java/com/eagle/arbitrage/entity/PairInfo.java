package com.eagle.arbitrage.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 交易对信息
 */
@Getter
@Setter
public class PairInfo {

    private String address;

    private String token0;

    private String token1;

    private BigInteger reserve0;

    private BigInteger reserve1;

    private BigInteger fee;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
