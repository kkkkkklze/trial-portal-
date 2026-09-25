package com.trialportal.block;

/**
 * 标记接口：以「潜行 + 手持物品右键」为交互入口的方块要实现它。
 * 原版在潜行且手持非空物品右键方块时会跳过 BlockState#use()，而本模组的结算恰恰是潜行右键——
 * 不实现这个接口的话症状是「空手潜行右键正常、手持物品潜行右键失灵」。
 */
public interface SneakInteractBlock
{
}
