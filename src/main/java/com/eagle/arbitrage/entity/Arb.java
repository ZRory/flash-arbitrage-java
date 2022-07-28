package com.eagle.arbitrage.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Arb {

    private String token;

    List<PairInfo> pairs;

}
