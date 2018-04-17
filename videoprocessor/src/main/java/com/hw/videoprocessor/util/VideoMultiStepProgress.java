package com.hw.videoprocessor.util;

/**
 * Created by huangwei on 2018/4/17 0017.
 * 用于计算多个步骤时的总进度
 */
public class VideoMultiStepProgress implements VideoProgressListener {

    private float[] mStepPercentes;
    private int mCurrentStep;
    private VideoProgressListener mListener;
    private float mStepBaseProgress;

    public VideoMultiStepProgress(float[] stepPercents, VideoProgressListener listener) {
        mStepPercentes = stepPercents;
        mListener = listener;
    }

    public void setCurrentStep(int stepIndex) {
        mCurrentStep = stepIndex;
        mStepBaseProgress = 0;
        for (int i = 0; i < stepIndex; i++) {
            mStepBaseProgress += mStepPercentes[i];
        }
    }

    @Override
    public void onProgress(float progress) {
        if (mListener != null) {
            float totalProgress = progress * mStepPercentes[mCurrentStep] + mStepBaseProgress;
            mListener.onProgress(totalProgress);
        }
    }

    public void setListener(VideoProgressListener listener) {
        mListener = listener;
    }
}
