package com.pab.ficc.cus.domain.base.bo;

import java.io.Serializable;

import lombok.Generated;

//"分页查询请求"
public class BaseBo implements Serializable {

    //("查询页码，默认为1")
    private long pageIndex = 1L;

   //("每页条数，默认一页20条")
    private long pageSize = 20L;

    @Generated
    public long getPageIndex() {
        return this.pageIndex;
    }

    @Generated
    public long getPageSize() {
        return this.pageSize;
    }

    @Generated
    public void setPageIndex(final long pageIndex) {
        this.pageIndex = pageIndex;
    }

    @Generated
    public void setPageSize(final long pageSize) {
        this.pageSize = pageSize;
    }

    @Generated
    public BaseBo() {

    }
}
