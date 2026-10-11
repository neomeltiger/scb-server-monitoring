package com.scb.monitoring.topology.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/** 화면 표시 형식 (기존 화면 값과 동일: "18.4%", "52%", "N/A", "1,420") */
final class Formats {

    private Formats() { }

    /** 소수 1자리 퍼센트 : 18.4% / 0.0% */
    static String percent1(BigDecimal v) {
        if (v == null) return "0.0%";
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 불필요한 0 없는 퍼센트 : 52% / 38.5%, NULL 이면 N/A */
    static String percentPlain(BigDecimal v) {
        if (v == null) return "N/A";
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString() + "%";
    }

    /** 천 단위 구분 : 1,420 */
    static String count(Integer v) {
        return new DecimalFormat("#,##0").format(v == null ? 0 : v);
    }
}
