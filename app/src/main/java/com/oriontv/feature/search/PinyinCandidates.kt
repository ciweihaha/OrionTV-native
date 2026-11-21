package com.oriontv.feature.search

/**
 * 拼音候选词典：无声调拼音 -> 常用汉字（按常用度排序，每音节最多 40 个）。
 * 由 gen-pinyin-dict.py 生成（生成器与数据源见 ~/workspace/automation/runs/2026-11-21-pinyin-dict/）：
 * 拼音->汉字来自 pinyin-pro 3.27.0（MIT）的 dict1；排序按 BLCU 25 亿字语料汉字字频表
 * （北语邢红兵，前 5000 字）；主读音在前、次读音在后；《通用规范汉字表》一级字表
 * 3500 字逐字核对、缺失的按字频位置补进主读音桶（48 字，见补丁）；另保留旧版独有的
 * 欸（ei）/呣（mou）；首选字手工覆写两处：guo 首选 国（否则“中国”变“中过”）、
 * lv 首选 绿。旧版（每音节 20 字、手写 1313 字频表）的问题：常用字“良”不在 liang
 * 的候选里（手写频表缺字 + 字典序决胜导致生僻字挤掉常用字），已修。
 * ü 用 v 表示（如 nv=女、lv=绿），与常见输入法惯例一致。
 *
 * 注：词典拆成 5 个分文件（PinyinCandidatesAtoD 等，按首字母分段），因为 JVM
 * 单个方法有 64KB 上限，12,747 条放一个文件的静态初始化方法会编不过
 * （MethodTooLargeException）。各分片由同一生成器写出，合并顺序即 a→z。
 */
val PINYIN_CANDIDATES: Map<String, List<String>> =
    PINYIN_CANDIDATES_A_TO_D + PINYIN_CANDIDATES_E_TO_J + PINYIN_CANDIDATES_K_TO_P +
        PINYIN_CANDIDATES_Q_TO_T + PINYIN_CANDIDATES_W_TO_Z
