package com.eagle.arbitrage.common.exception;

import com.eagle.arbitrage.common.enums.BizCodeEnum;
import com.eagle.arbitrage.common.enums.BizEnum;

import java.io.Serializable;

public class BizException extends RuntimeException implements Serializable {
    private static final long serialVersionUID = 2728936692069322518L;
    private BizEnum errorCode;
    private String errorMessage;

    public BizException() {
        super(BizCodeEnum.OPERATION_FAILED.getDesc());
        this.errorCode = BizCodeEnum.OPERATION_FAILED;
        this.errorMessage = this.errorCode.getDesc();
    }

    public BizException(BizEnum errorCode) {
        super(errorCode.getDesc());
        this.errorCode = errorCode;
        this.errorMessage = errorCode.getDesc();
    }

    public BizException(BizEnum errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public BizException(BizEnum errorCode, String errorMessage, Throwable exception) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        super.initCause(exception);
    }

    public BizEnum getErrorCode() {
        return this.errorCode;
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public void setErrorCode(BizEnum errorCode) {
        this.errorCode = errorCode;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public static boolean isBizException(Throwable exception) {
        return exception instanceof BizException;
    }

    public static boolean isErrorException(BizEnum errorCode) {
        return BizCodeEnum.SYSTEM_ERROR.equals(errorCode) || BizCodeEnum.CALL_SERVICE_ERROR.equals(errorCode) || BizCodeEnum.CALL_SERVICE_ERROR.equals(errorCode) || BizCodeEnum.URL_REQUEST_ERROR.equals(errorCode) || BizCodeEnum.REQUEST_ERROR.equals(errorCode) || BizCodeEnum.PROCESS_FAIL.equals(errorCode);
    }
}
