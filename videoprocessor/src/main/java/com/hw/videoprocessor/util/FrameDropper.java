package com.hw.videoprocessor.util;

/**
 * Created by huangwei on 2019/7/20.
 */
public class FrameDropper {
    private int srcFrameRate;
    private int dstFrameRate;
    private boolean disable;

    private int dropCount;
    private int keepCount;

    public FrameDropper(int srcFrameRate,int dstFrameRate){
        this.srcFrameRate = srcFrameRate;
        this.dstFrameRate = dstFrameRate;
        if(srcFrameRate<=dstFrameRate){
            CL.e("原始帧率:"+srcFrameRate+"小于目标帧率:"+dstFrameRate+"，不支持补帧");
            disable = true;
        }
    }

    public boolean checkDrop(int frameIndex){
        if(disable){
            return false;
        }
        if(frameIndex==0){
            //第一帧保留
            keepCount++;
            return false;
        }
        float targetDropRate = (srcFrameRate-dstFrameRate)/(float)srcFrameRate;
        float ifDropRate = (dropCount+1)/(float)(dropCount+keepCount);
        float ifNotDropRate = (dropCount)/(float)(dropCount+keepCount+1);

        boolean drop = (Math.abs(ifDropRate -targetDropRate) < Math.abs(ifNotDropRate -targetDropRate));

        if(drop){
            dropCount++;
        }else{
            keepCount++;
        }
//        CL.v("目前丢帧率:"+dropCount/(float)(dropCount+keepCount)+" 目标丢帧率:"+targetDropRate);
        return drop;
    }

    public void printResult(){
        if(disable){
            return;
        }
        int totalFrame = dropCount+keepCount;
        float duration = totalFrame/(float)srcFrameRate;
        float realFrameRate = keepCount / duration;
        float targetDropRate = (srcFrameRate-dstFrameRate)/(float)srcFrameRate;
        CL.i("最终帧率为:"+realFrameRate);
        CL.i("实际丢帧率:"+dropCount/(float)(dropCount+keepCount)+" 目标丢帧率:"+targetDropRate);
    }

}
