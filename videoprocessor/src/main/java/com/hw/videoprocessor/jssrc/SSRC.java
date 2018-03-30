/*
 * This program(except FFT and Bessel function part) is distributed under
 * LGPL. See LGPL.txt for details. But, if you make a new program with derived
 * code from this program,I strongly wish that my name and derived code are
 * indicated explicitly.
 */

package com.hw.videoprocessor.jssrc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Random;


/**
 * Shibatch Sampling Rate Converter.
 *
 * @author <a href="shibatch@users.sourceforge.net">Naoki Shibata</a>
 * @author <a href="mailto:vavivavi@yahoo.co.jp">Naohide Sano</a> (nsano)
 * @author Maksim Khadkevich (a couple of minor changes)
 * @version 0.00 060127 nsano port to java version <br>
 */
public class SSRC {

    /** */
    private ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    /** */
    private SplitRadixFft fft = new SplitRadixFft();

    /** */
    private static final String VERSION = "1.30";

    /** */
    private double AA = 170;

    /** */
    private double DF = 100;

    /** */
    private int FFTFIRLEN = 65536;

    /** */
//  private static final int M = 15;

    /** */
    private static final int RANDBUFLEN = 65536;

    /** */
    private int RINT(double x) {
        return ((x) >= 0 ? ((int) ((x) + 0.5)) : ((int) ((x) - 0.5)));
    }

    /** */
    private static final int scoeffreq[] = {
            0, 48000, 44100, 37800, 32000, 22050, 48000, 44100
    };

    /** */
    private static final int scoeflen[] = {
            1, 16, 20, 16, 16, 15, 16, 15
    };

    /** */
    private static final int samp[] = {
            8, 18, 27, 8, 8, 8, 10, 9
    };

    /** */
    private static final double[][] shapercoefs = {
            {-1}, // triangular dither

            {-2.8720729351043701172, 5.0413231849670410156, -6.2442994117736816406, 5.8483986854553222656,
                    -3.7067542076110839844, 1.0495119094848632812, 1.1830236911773681641, -2.1126792430877685547,
                    1.9094531536102294922, -0.99913084506988525391, 0.17090806365013122559, 0.32615602016448974609,
                    -0.39127644896507263184, 0.26876461505889892578, -0.097676105797290802002, 0.023473845794796943665,
            }, // 48k, N=16, amp=18

            {-2.6773197650909423828, 4.8308925628662109375, -6.570110321044921875, 7.4572014808654785156,
                    -6.7263274192810058594, 4.8481650352478027344, -2.0412089824676513672, -0.7006359100341796875,
                    2.9537565708160400391, -4.0800385475158691406, 4.1845216751098632812, -3.3311812877655029297,
                    2.1179926395416259766, -0.879302978515625, 0.031759146600961685181, 0.42382788658142089844,
                    -0.47882103919982910156, 0.35490813851356506348, -0.17496839165687561035, 0.060908168554306030273,
            }, // 44.1k, N=20, amp=27

            {-1.6335992813110351562, 2.2615492343902587891, -2.4077029228210449219, 2.6341717243194580078,
                    -2.1440362930297851562, 1.8153258562088012695, -1.0816224813461303711, 0.70302653312683105469,
                    -0.15991993248462677002, -0.041549518704414367676, 0.29416576027870178223, -0.2518316805362701416,
                    0.27766478061676025391, -0.15785403549671173096, 0.10165894031524658203, -0.016833892092108726501,
            }, // 37.8k, N=16

            {-0.82901298999786376953, 0.98922657966613769531, -0.59825712442398071289, 1.0028809309005737305,
                    -0.59938216209411621094, 0.79502451419830322266, -0.42723315954208374023, 0.54492527246475219727,
                    -0.30792605876922607422, 0.36871799826622009277, -0.18792048096656799316, 0.2261127084493637085,
                    -0.10573341697454452515, 0.11435490846633911133, -0.038800679147243499756, 0.040842197835445404053,
            }, // 32k, N=16

            {-0.065229974687099456787, 0.54981261491775512695, 0.40278548002243041992, 0.31783768534660339355,
                    0.28201797604560852051, 0.16985194385051727295, 0.15433363616466522217, 0.12507140636444091797,
                    0.08903945237398147583, 0.064410120248794555664, 0.047146003693342208862, 0.032805237919092178345,
                    0.028495194390416145325, 0.011695005930960178375, 0.011831838637590408325,
            }, // 22.05k, N=15

            {-2.3925774097442626953, 3.4350297451019287109, -3.1853709220886230469, 1.8117271661758422852,
                    0.20124770700931549072, -1.4759907722473144531, 1.7210904359817504883, -0.97746700048446655273,
                    0.13790138065814971924, 0.38185903429985046387, -0.27421241998672485352, -0.066584214568138122559,
                    0.35223302245140075684, -0.37672343850135803223, 0.23964276909828186035, -0.068674825131893157959,
            }, // 48k, N=16, amp=10

            {-2.0833916664123535156, 3.0418450832366943359, -3.2047898769378662109, 2.7571926116943359375,
                    -1.4978630542755126953, 0.3427594602108001709, 0.71733748912811279297, -1.0737057924270629883,
                    1.0225815773010253906, -0.56649994850158691406, 0.20968692004680633545, 0.065378531813621520996,
                    -0.10322438180446624756, 0.067442022264003753662, 0.00495197344571352005,
            }, // 44.1k, N=15, amp=9
    };

    /** */
    private double[][] shapebuf;

    /** */
    private int shaper_type, shaper_len, shaper_clipmin, shaper_clipmax;

    /** */
    private double[] randbuf;

    /** */
    private int randptr;

    /** */
    private boolean quiet = false;

    /** */
    private int lastshowed2;

    /** */
    private long starttime, lastshowed;

    /** */
    private static final int POOLSIZE = 97;

    /** */
    public int init_shaper(int freq, int nch, int min, int max, int dtype, int pdf, double noiseamp) {
        int i;
        int[] pool = new int[POOLSIZE];

        for (i = 1; i < 6; i++) {
            if (freq == scoeffreq[i]) {
                break;
            }
        }
        if ((dtype == 3 || dtype == 4) && i == 6) {
            System.err.printf("Warning: ATH based noise shaping for destination frequency %dHz is not available, using triangular dither\n", freq);
        }
        if (dtype == 2 || i == 6) {
            i = 0;
        }
        if (dtype == 4 && (i == 1 || i == 2)) {
            i += 5;
        }

        shaper_type = i;

        shapebuf = new double[nch][];
        shaper_len = scoeflen[shaper_type];

        for (i = 0; i < nch; i++) {
            shapebuf[i] = new double[shaper_len];
        }

        shaper_clipmin = min;
        shaper_clipmax = max;

        randbuf = new double[RANDBUFLEN];

        Random random = new Random(System.currentTimeMillis());
        for (i = 0; i < POOLSIZE; i++) {
            pool[i] = random.nextInt();
        }

        switch (pdf) {
            case 0: // rectangular
                for (i = 0; i < RANDBUFLEN; i++) {
                    int r, p;

                    p = random.nextInt() % POOLSIZE;
                    r = pool[p];
                    pool[p] = random.nextInt();
                    randbuf[i] = noiseamp * (((double) r) / Integer.MAX_VALUE - 0.5);
                }
                break;

            case 1: // triangular
                for (i = 0; i < RANDBUFLEN; i++) {
                    int r1, r2, p;

                    p = random.nextInt() % POOLSIZE;
                    r1 = pool[p];
                    pool[p] = random.nextInt();
                    p = random.nextInt() % POOLSIZE;
                    r2 = pool[p];
                    pool[p] = random.nextInt();
                    randbuf[i] = noiseamp * ((((double) r1) / Integer.MAX_VALUE) - (((double) r2) / Integer.MAX_VALUE));
                }
                break;

            case 2: // gaussian
            {
                int sw = 0;
                double t = 0, u = 0;

                for (i = 0; i < RANDBUFLEN; i++) {
                    double r;
                    int p;

                    if (sw == 0) {
                        sw = 1;

                        p = random.nextInt() % POOLSIZE;
                        r = ((double) pool[p]) / Integer.MAX_VALUE;
                        pool[p] = random.nextInt();
                        if (r == 1.0) {
                            r = 0.0;
                        }

                        t = Math.sqrt(-2 * Math.log(1 - r));

                        p = random.nextInt() % POOLSIZE;
                        r = ((double) pool[p]) / Integer.MAX_VALUE;
                        pool[p] = random.nextInt();

                        u = 2 * Math.PI * r;

                        randbuf[i] = noiseamp * t * Math.cos(u);
                    } else {
                        sw = 0;

                        randbuf[i] = noiseamp * t * Math.sin(u);
                    }
                }
            }
            break;
        }

        randptr = 0;

        if (dtype == 0 || dtype == 1) {
            return 1;
        }
        return samp[shaper_type];
    }

    /** */
    public int do_shaping(double s, double[] peak, int dtype, int ch) {
        double u, h;
        int i;

        if (dtype == 1) {
            s += randbuf[randptr++ & (RANDBUFLEN - 1)];

            if (s < shaper_clipmin) {
                double d = s / shaper_clipmin;
                peak[0] = peak[0] < d ? d : peak[0];
                s = shaper_clipmin;
            }
            if (s > shaper_clipmax) {
                double d = s / shaper_clipmax;
                peak[0] = peak[0] < d ? d : peak[0];
                s = shaper_clipmax;
            }

            return RINT(s);
        }

        h = 0;
        for (i = 0; i < shaper_len; i++) {
            h += shapercoefs[shaper_type][i] * shapebuf[ch][i];
        }
        s += h;
        u = s;
        s += randbuf[randptr++ & (RANDBUFLEN - 1)];

        for (i = shaper_len - 2; i >= 0; i--) {
            shapebuf[ch][i + 1] = shapebuf[ch][i];
        }

        if (s < shaper_clipmin) {
            double d = s / shaper_clipmin;
            peak[0] = peak[0] < d ? d : peak[0];
            s = shaper_clipmin;
            shapebuf[ch][0] = s - u;

            if (shapebuf[ch][0] > 1) {
                shapebuf[ch][0] = 1;
            }
            if (shapebuf[ch][0] < -1) {
                shapebuf[ch][0] = -1;
            }
        } else if (s > shaper_clipmax) {
            double d = s / shaper_clipmax;
            peak[0] = peak[0] < d ? d : peak[0];
            s = shaper_clipmax;
            shapebuf[ch][0] = s - u;

            if (shapebuf[ch][0] > 1) {
                shapebuf[ch][0] = 1;
            }
            if (shapebuf[ch][0] < -1) {
                shapebuf[ch][0] = -1;
            }
        } else {
            s = RINT(s);
            shapebuf[ch][0] = s - u;
        }

        return (int) s;
    }

    /** */
    private void quit_shaper(int nch) {
    }

    /** */
    private double alpha(double a) {
        if (a <= 21) {
            return 0;
        }
        if (a <= 50) {
            return 0.5842 * Math.pow(a - 21, 0.4) + 0.07886 * (a - 21);
        }
        return 0.1102 * (a - 8.7);
    }

    /** */
    private double win(double n, int len, double alp, double iza) {
        return I0Bessel.value(alp * Math.sqrt(1 - 4 * n * n / (((double) len - 1) * ((double) len - 1)))) / iza;
    }

    /** */
    private double sinc(double x) {
        return x == 0 ? 1 : Math.sin(x) / x;
    }

    /** */
    private double hn_lpf(int n, double lpf, double fs) {
        double t = 1 / fs;
        double omega = 2 * Math.PI * lpf;
        return 2 * lpf * t * sinc(n * omega * t);
    }

    /** */
    private void usage() {
        System.err.printf("http://shibatch.sourceforge.net/\n\n");
        System.err.printf("usage: ssrc [<options>] <source wav file> <destination wav file>\n");
        System.err.printf("options : --rate <sampling rate>     output sample rate\n");
        System.err.printf("          --att <attenuation(dB)>    attenuate signal\n");
        System.err.printf("          --bits <number of bits>    output quantization bit length\n");
        System.err.printf("          --tmpfile <file name>      specify temporal file\n");
        System.err.printf("          --twopass                  two pass processing to avoid clipping\n");
        System.err.printf("          --normalize                normalize the wave file\n");
        System.err.printf("          --quiet                    nothing displayed except error\n");
        System.err.printf("          --dither [<type>]          dithering\n");
        System.err.printf("                                       0 : no dither\n");
        System.err.printf("                                       1 : no noise shaping\n");
        System.err.printf("                                       2 : triangular spectral shape\n");
        System.err.printf("                                       3 : ATH based noise shaping\n");
        System.err.printf("                                       4 : less dither amplitude than type 3\n");
        System.err.printf("          --pdf <type> [<amp>]       select p.d.f. of noise\n");
        System.err.printf("                                       0 : rectangular\n");
        System.err.printf("                                       1 : triangular\n");
        System.err.printf("                                       2 : Gaussian\n");
        System.err.printf("          --profile <type>           specify profile\n");
        System.err.printf("                                       standard : the default quality\n");
        System.err.printf("                                       fast     : fast, not so bad quality\n");
    }

    /** */
    private void fmterr(int x) {
        throw new IllegalStateException("unknown error " + x);
    }

    /** */
    private void setstarttime() {
        starttime = System.currentTimeMillis();
        lastshowed = 0;
        lastshowed2 = -1;
    }

    /** */
    private void showprogress(double p) {
        int eta, pc;
        long t;
        if (quiet) {
            return;
        }

        t = System.currentTimeMillis() - starttime;
        if (p == 0) {
            eta = 0;
        } else {
            eta = (int) (t * (1 - p) / p);
        }

        pc = (int) (p * 100);

        if (pc != lastshowed2 || t != lastshowed) {
            System.err.printf(" %3d%% processed", pc);
            lastshowed2 = pc;
        }
        if (t != lastshowed) {
            System.err.printf(", ETA =%4dmsec", eta);
            lastshowed = t;
        }
        System.err.printf("\r");
        System.err.flush();
    }

    /** */
    private int gcd(int x, int y) {
        int t;

        while (y != 0) {
            t = x % y;
            x = y;
            y = t;
        }
        return x;
    }

    /**
     * @param fpi
     * @param fpo
     * @param nch
     * @param bps
     * @param dbps     sizeof(double)?
     * @param sfrq
     * @param dfrq
     * @param gain
     * @param chanklen
     * @param twopass
     * @param dither
     * @return
     * @throws IOException
     */
    public double upsample(InputStream fpi, OutputStream fpo, int nch, int bps, int dbps, int sfrq, int dfrq, double gain, int chanklen, boolean twopass, int dither) throws IOException {
        int frqgcd, osf = 0, fs1, fs2;
        double[][] stage1;
        double[] stage2;
        int n1, n1x, n1y, n2, n2b;
        int filter2len;
        int[] f1order, f1inc;
        int[] fft_ip = null;
        double[] fft_w = null;
        ByteBuffer rawinbuf, rawoutbuf;
        double[] inbuf, outbuf;
        double[][] buf1, buf2;
        double[] peak = new double[]{0};
        int spcount = 0;
        int i, j;

//        System.err.println("upsample");

        filter2len = FFTFIRLEN; // stage 2 filter length

        // Make stage 1 filter

        {
            double aa = AA; // stop band attenuation(dB)
            double lpf, d, df, alp, iza;
//          double delta;
            double guard = 2;

            frqgcd = gcd(sfrq, dfrq);

            fs1 = sfrq / frqgcd * dfrq;

            if (fs1 / dfrq == 1) {
                osf = 1;
            } else if (fs1 / dfrq % 2 == 0) {
                osf = 2;
            } else if (fs1 / dfrq % 3 == 0) {
                osf = 3;
            } else {
                throw new IllegalArgumentException(
                        String.format("Resampling from %dHz to %dHz is not supported.\n" +
                                "%d/gcd(%d,%d)=%d must be divided by 2 or 3.\n",
                                sfrq, dfrq, sfrq, sfrq, dfrq, fs1 / dfrq));
            }

            df = (dfrq * osf / 2 - sfrq / 2) * 2 / guard;
            lpf = sfrq / 2 + (dfrq * osf / 2 - sfrq / 2) / guard;

//          delta = Math.pow(10, -aa / 20);
            if (aa <= 21) {
                d = 0.9222;
            } else {
                d = (aa - 7.95) / 14.36;
            }

            n1 = (int) (fs1 / df * d + 1);
            if (n1 % 2 == 0) {
                n1++;
            }

            alp = alpha(aa);
            iza = I0Bessel.value(alp);
// System.err.printf("iza = %g\n",iza);

            n1y = fs1 / sfrq;
            n1x = n1 / n1y + 1;

            f1order = new int[n1y * osf];
            for (i = 0; i < n1y * osf; i++) {
                f1order[i] = fs1 / sfrq - (i * (fs1 / (dfrq * osf))) % (fs1 / sfrq);
                if (f1order[i] == fs1 / sfrq) {
                    f1order[i] = 0;
                }
            }

            f1inc = new int[n1y * osf];
            for (i = 0; i < n1y * osf; i++) {
                f1inc[i] = f1order[i] < fs1 / (dfrq * osf) ? nch : 0;
                if (f1order[i] == fs1 / sfrq) {
                    f1order[i] = 0;
                }
            }

            stage1 = new double[n1y][n1x];

            for (i = -(n1 / 2); i <= n1 / 2; i++) {
                stage1[(i + n1 / 2) % n1y][(i + n1 / 2) / n1y] = win(i, n1, alp, iza) * hn_lpf(i, lpf, fs1) * fs1 / sfrq;
            }
        }

        // Make stage 2 filter

        {
            double aa = AA; // stop band attenuation(dB)
            double lpf, d, df, alp, iza;
//          double delta;
            int ipsize, wsize;

//          delta = Math.pow(10, -aa / 20);
            if (aa <= 21) {
                d = 0.9222;
            } else {
                d = (aa - 7.95) / 14.36;
            }

            fs2 = dfrq * osf;

            for (i = 1; ; i = i * 2) {
                n2 = filter2len * i;
                if (n2 % 2 == 0) {
                    n2--;
                }
                df = (fs2 * d) / (n2 - 1);
                lpf = sfrq / 2;
                if (df < DF) {
                    break;
                }
            }

            alp = alpha(aa);

            iza = I0Bessel.value(alp);

            for (n2b = 1; n2b < n2; n2b *= 2) {
            }
            n2b *= 2;

            stage2 = new double[n2b];

            for (i = -(n2 / 2); i <= n2 / 2; i++) {
                stage2[i + n2 / 2] = win(i, n2, alp, iza) * hn_lpf(i, lpf, fs2) / n2b * 2;
            }

            ipsize = (int) (2 + Math.sqrt(n2b));
            fft_ip = new int[ipsize];
            fft_ip[0] = 0;
            wsize = n2b / 2;
            fft_w = new double[wsize];

            fft.rdft(n2b, 1, stage2, fft_ip, fft_w);
        }

        // Apply filters

        setstarttime();

        {
            int n2b2 = n2b / 2;
            // inbuffs1Tv???
            int rp;
            // disposesfrqTv?
            int ds;
            // ?t@Cinbuf?lvZ stage2 filternTv?
            int nsmplwrt1;
            // ?t@Cinbuf?lvZ stage2 filternTv?
            int nsmplwrt2 = 0;
            // stage1 filter?oTv?n1y*osf]
            int s1p;
            boolean init;
            boolean ending;
            int sumread, sumwrite;
            int osc;
            int ip, ip_backup;
            int s1p_backup, osc_backup;
            int ch, p;
            int inbuflen;
            int delay = 0;

            buf1 = new double[nch][n2b2 / osf + 1];

            buf2 = new double[nch][n2b];

            rawinbuf = ByteBuffer.allocate(nch * (n2b2 + n1x) * bps); // ,bps
            rawoutbuf = ByteBuffer.allocate(nch * (n2b2 / osf + 1) * dbps); // ,dbps

            inbuf = new double[nch * (n2b2 + n1x)];
            outbuf = new double[nch * (n2b2 / osf + 1)];

            s1p = 0;
            rp = 0;
            ds = 0;
            osc = 0;

            init = true;
            ending = false;
            inbuflen = n1 / 2 / (fs1 / sfrq) + 1;
            delay = (int) ((double) n2 / 2 / (fs2 / dfrq));

            sumread = sumwrite = 0;

            while (true) {
                int nsmplread, toberead, toberead2;

                toberead2 = toberead = (int) (Math.floor((double) n2b2 * sfrq / (dfrq * osf)) + 1 + n1x - inbuflen);
                if (toberead + sumread > chanklen) {
                    toberead = chanklen - sumread;
                }

                rawinbuf.position(0);
                rawinbuf.limit(Math.min(rawinbuf.limit(), bps * nch * toberead));
//                rawinbuf.limit(bps * nch * toberead);

                byte[] tempData = new byte[rawinbuf.limit()];
                nsmplread = fpi.read(tempData);
                if (nsmplread < 0) {
                    nsmplread = 0;
                }

                if (nsmplread < rawinbuf.limit()) {
                    chanklen = sumread + nsmplread / bps * nch;
                }

                rawinbuf.limit(nsmplread);
                rawinbuf = ByteBuffer.wrap(tempData);
                rawinbuf.position(nsmplread);

                rawinbuf.flip();
                nsmplread /= bps * nch;

                switch (bps) {
                    case 1:
                        for (i = 0; i < nsmplread * nch; i++)
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7f) * ((double) rawinbuf.get(i) - 128);
                        break;

                    case 2:
                        for (i = 0; i < nsmplread * nch; i++) {
                            int v = rawinbuf.order(byteOrder).asShortBuffer().get(i);
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fff) * v;
                        }
                        break;

                    case 3:
                        for (i = 0; i < nsmplread * nch; i++) {
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fffff) *
                                    ((rawinbuf.get(i * 3) << 0) |
                                            (rawinbuf.get(i * 3 + 1) << 8) |
                                            (rawinbuf.get(i * 3 + 2) << 16));
                        }
                        break;

                    case 4:
                        for (i = 0; i < nsmplread * nch; i++) {
                            int v = rawinbuf.order(byteOrder).asIntBuffer().get(i);
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fffffff) * v;
                        }
                        break;
                }

                for (; i < nch * toberead2; i++) {
                    inbuf[nch * inbuflen + i] = 0;
                }

                inbuflen += toberead2;

                sumread += nsmplread;

                ending = sumread >= chanklen;
//                ending = fpi.available() == 0 || sumread >= chanklen;

//              nsmplwrt1 = ((rp - 1) * sfrq / fs1 + inbuflen - n1x) * dfrq * osf / sfrq;
//              if (nsmplwrt1 > n2b2) { nsmplwrt1 = n2b2; }
                nsmplwrt1 = n2b2;

                // apply stage 1 filter

                ip = ((sfrq * (rp - 1) + fs1) / fs1) * nch; // inbuf

                s1p_backup = s1p;
                ip_backup = ip;
                osc_backup = osc;

                for (ch = 0; ch < nch; ch++) {
                    int op = ch; // outbuf
//                  int fdo = fs1 / (dfrq * osf);
                    int no = n1y * osf;

                    s1p = s1p_backup;
                    ip = ip_backup + ch;

                    switch (n1x) {
                        case 7:
                            for (p = 0; p < nsmplwrt1; p++) {
                                int s1o = f1order[s1p];

                                buf2[ch][p] =
                                        stage1[s1o][0] * inbuf[ip + 0 * nch] +
                                                stage1[s1o][1] * inbuf[ip + 1 * nch] +
                                                stage1[s1o][2] * inbuf[ip + 2 * nch] +
                                                stage1[s1o][3] * inbuf[ip + 3 * nch] +
                                                stage1[s1o][4] * inbuf[ip + 4 * nch] +
                                                stage1[s1o][5] * inbuf[ip + 5 * nch] +
                                                stage1[s1o][6] * inbuf[ip + 6 * nch];

                                ip += f1inc[s1p];

                                s1p++;
                                if (s1p == no) {
                                    s1p = 0;
                                }
                            }
                            break;

                        case 9:
                            for (p = 0; p < nsmplwrt1; p++) {
                                int s1o = f1order[s1p];

                                buf2[ch][p] =
                                        stage1[s1o][0] * inbuf[ip + 0 * nch] +
                                                stage1[s1o][1] * inbuf[ip + 1 * nch] +
                                                stage1[s1o][2] * inbuf[ip + 2 * nch] +
                                                stage1[s1o][3] * inbuf[ip + 3 * nch] +
                                                stage1[s1o][4] * inbuf[ip + 4 * nch] +
                                                stage1[s1o][5] * inbuf[ip + 5 * nch] +
                                                stage1[s1o][6] * inbuf[ip + 6 * nch] +
                                                stage1[s1o][7] * inbuf[ip + 7 * nch] +
                                                stage1[s1o][8] * inbuf[ip + 8 * nch];

                                ip += f1inc[s1p];

                                s1p++;
                                if (s1p == no) {
                                    s1p = 0;
                                }
                            }
                            break;

                        default:
                            for (p = 0; p < nsmplwrt1; p++) {
                                double tmp = 0;
                                int ip2 = ip;

                                int s1o = f1order[s1p];

                                for (i = 0; i < n1x; i++) {
                                    tmp += stage1[s1o][i] * inbuf[ip2];
                                    ip2 += nch;
                                }
                                buf2[ch][p] = tmp;

                                ip += f1inc[s1p];

                                s1p++;
                                if (s1p == no) {
                                    s1p = 0;
                                }
                            }
                            break;
                    }

                    osc = osc_backup;

                    // apply stage 2 filter

                    for (p = nsmplwrt1; p < n2b; p++) {
                        buf2[ch][p] = 0;
                    }

//for(i=0;i<n2b2;i++) { System.err.printf("%d:%g",i,buf2[ch][i]); }

                    fft.rdft(n2b, 1, buf2[ch], fft_ip, fft_w);

                    buf2[ch][0] = stage2[0] * buf2[ch][0];
                    buf2[ch][1] = stage2[1] * buf2[ch][1];

                    for (i = 1; i < n2b / 2; i++) {
                        double re, im;

                        re = stage2[i * 2] * buf2[ch][i * 2] - stage2[i * 2 + 1] * buf2[ch][i * 2 + 1];
                        im = stage2[i * 2 + 1] * buf2[ch][i * 2] + stage2[i * 2] * buf2[ch][i * 2 + 1];

//System.err.printf("%d : %g %g %g %g %g %g\n",i,stage2[i*2],stage2[i*2+1],buf2[ch][i*2],buf2[ch][i*2+1],re,im);

                        buf2[ch][i * 2] = re;
                        buf2[ch][i * 2 + 1] = im;
                    }

                    fft.rdft(n2b, -1, buf2[ch], fft_ip, fft_w);

                    for (i = osc, j = 0; i < n2b2; i += osf, j++) {
                        double f = (buf1[ch][j] + buf2[ch][i]);
                        outbuf[op + j * nch] = f;
                    }

                    nsmplwrt2 = j;

                    osc = i - n2b2;

                    for (j = 0; i < n2b; i += osf, j++) {
                        buf1[ch][j] = buf2[ch][i];
                    }
                }

                rp += nsmplwrt1 * (sfrq / frqgcd) / osf;

                rawoutbuf.clear();
                if (twopass) {
                    for (i = 0; i < nsmplwrt2 * nch; i++) {
                        double f = outbuf[i] > 0 ? outbuf[i] : -outbuf[i];
                        peak[0] = peak[0] < f ? f : peak[0];
                        rawoutbuf.asDoubleBuffer().put(i, outbuf[i]);
                    }
                } else {
                    switch (dbps) {
                        case 1: {
                            double gain2 = gain * 0x7f;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x80) {
                                        double d = (double) s / -0x80;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x80;
                                    }
                                    if (0x7f < s) {
                                        double d = (double) s / 0x7f;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7f;
                                    }
                                }

                                rawoutbuf.put(i, (byte) (s + 0x80));

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                        case 2: {
                            double gain2 = gain * 0x7fff;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x8000) {
                                        double d = (double) s / -0x8000;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x8000;
                                    }
                                    if (0x7fff < s) {
                                        double d = (double) s / 0x7fff;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7fff;
                                    }
                                }

                                rawoutbuf.order(byteOrder).asShortBuffer().put(i, (short) s);

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                        case 3: {
                            double gain2 = gain * 0x7fffff;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x800000) {
                                        double d = (double) s / -0x800000;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x800000;
                                    }
                                    if (0x7fffff < s) {
                                        double d = (double) s / 0x7fffff;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7fffff;
                                    }
                                }

                                rawoutbuf.put(i * 3, (byte) (s & 255));
                                s >>= 8;
                                rawoutbuf.put(i * 3 + 1, (byte) (s & 255));
                                s >>= 8;
                                rawoutbuf.put(i * 3 + 2, (byte) (s & 255));

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                    }
                }

                if (!init) {
                    if (ending) {
                        if ((double) sumread * dfrq / sfrq + 2 > sumwrite + nsmplwrt2) {
                            rawoutbuf.position(0);
                            rawoutbuf.limit(dbps * nch * nsmplwrt2);
                            writeBuffers(fpo, rawoutbuf);
                            sumwrite += nsmplwrt2;
                        } else {
                            rawoutbuf.position(0);
                            int limitData = (int) (dbps * nch * (Math.floor((double) sumread * dfrq / sfrq) + 2 - sumwrite));
                            if (limitData > 0) {
                                rawoutbuf.limit(limitData);
                                writeBuffers(fpo, rawoutbuf);
                            }
                            break;
                        }
                    } else {
                        rawoutbuf.position(0);
                        rawoutbuf.limit(dbps * nch * nsmplwrt2);
                        writeBuffers(fpo, rawoutbuf);
                        sumwrite += nsmplwrt2;
                    }
                } else {

                    if (nsmplwrt2 < delay) {
                        delay -= nsmplwrt2;
                    } else {
                        if (ending) {
                            if ((double) sumread * dfrq / sfrq + 2 > sumwrite + nsmplwrt2 - delay) {
                                rawoutbuf.position(dbps * nch * delay);
                                rawoutbuf.limit(dbps * nch * (nsmplwrt2 - delay));
                                writeBuffers(fpo, rawoutbuf);
                                sumwrite += nsmplwrt2 - delay;
                            } else {
                                rawoutbuf.position(dbps * nch * delay);
                                rawoutbuf.limit((int) (dbps * nch * (Math.floor((double) sumread * dfrq / sfrq) + 2 + sumwrite + nsmplwrt2 - delay)));
                                writeBuffers(fpo, rawoutbuf);
                                break;
                            }
                        } else {
                            rawoutbuf.position(dbps * nch * delay);
                            rawoutbuf.limit(dbps * nch * (nsmplwrt2));
                            writeBuffers(fpo, rawoutbuf);
                            sumwrite += nsmplwrt2 - delay;
                            init = false;
                        }
                    }
                }

                {
                    ds = (rp - 1) / (fs1 / sfrq);

                    assert (inbuflen >= ds);

                    System.arraycopy(inbuf, nch * ds, inbuf, 0, nch * (inbuflen - ds)); // memmove TODO overlap
                    inbuflen -= ds;
                    rp -= ds * (fs1 / sfrq);
                }

                if ((spcount++ & 7) == 7) {
                    showprogress((double) sumread / chanklen);
                }
            }
        }

        showprogress(1);

        return peak[0];
    }

    /** */
    public double downsample(InputStream fpi, OutputStream fpo, int nch, int bps, int dbps, int sfrq, int dfrq, double gain, int chanklen, boolean twopass, int dither) throws IOException {
        int frqgcd, osf = 0, fs1, fs2;
        double[] stage1;
        double[][] stage2;
        int n2, n2x, n2y, n1, n1b;
        int filter1len;
        int[] f2order, f2inc;
        int[] fft_ip = null;
        double[] fft_w = null;
        ByteBuffer rawinbuf, rawoutbuf;
        double[] inbuf, outbuf;
        double[][] buf1, buf2;
        int i, j;
        int spcount = 0;
        double[] peak = new double[]{0};

//        System.err.println("downsample");

        filter1len = FFTFIRLEN; // stage 1 filter length

        // Make stage 1 filter

        {
            double aa = AA; // stop band attenuation(dB)
            double lpf, d, df, alp, iza;
//          double delta;
            int ipsize, wsize;

            frqgcd = gcd(sfrq, dfrq);

            if (dfrq / frqgcd == 1) {
                osf = 1;
            } else if (dfrq / frqgcd % 2 == 0) {
                osf = 2;
            } else if (dfrq / frqgcd % 3 == 0) {
                osf = 3;
            } else {
                throw new IllegalArgumentException(
                        String.format("Resampling from %dHz to %dHz is not supported.\n" +
                                "%d/gcd(%d,%d)=%d must be divided by 2 or 3.",
                                sfrq, dfrq, dfrq, sfrq, dfrq, dfrq / frqgcd));
            }

            fs1 = sfrq * osf;

//          delta = Math.pow(10, -aa / 20);
            if (aa <= 21) {
                d = 0.9222;
            } else {
                d = (aa - 7.95) / 14.36;
            }

            n1 = filter1len;
            for (i = 1; ; i = i * 2) {
                n1 = filter1len * i;
                if (n1 % 2 == 0) {
                    n1--;
                }
                df = (fs1 * d) / (n1 - 1);
                lpf = (dfrq - df) / 2;
                if (df < DF) {
                    break;
                }
            }

            alp = alpha(aa);

            iza = I0Bessel.value(alp);
//System.err.printf("iza %f, alp: %f\n", iza, alp); // OK

            for (n1b = 1; n1b < n1; n1b *= 2) {
            }
            n1b *= 2;

            stage1 = new double[n1b];

            for (i = -(n1 / 2); i <= n1 / 2; i++) {
                stage1[i + n1 / 2] = win(i, n1, alp, iza) * hn_lpf(i, lpf, fs1) * fs1 / sfrq / n1b * 2;
//System.err.printf("1: %06d: %e\n", i + n1 / 2, stage1[i + n1 / 2]); // OK
            }

            ipsize = (int) (2 + Math.sqrt(n1b));
            fft_ip = new int[ipsize];
            fft_ip[0] = 0;
            wsize = n1b / 2;
            fft_w = new double[wsize];

            fft.rdft(n1b, 1, stage1, fft_ip, fft_w);
//for (i = -(n1 / 2); i <= n1 / 2; i++) {
// System.err.printf("1': %06d: %e\n", i + n1 / 2, stage1[i + n1 / 2]);
//}
//for (i = 0; i < ipsize; i++) {
// System.err.printf("ip: %06d: %d\n", i, fft_ip[i]); // OK
//}
//for (i = 0; i < wsize; i++) {
// System.err.printf("w: %06d: %e\n", i, fft_w[i]); // OK
//}
        }

        // Make stage 2 filter

        if (osf == 1) {
            fs2 = sfrq / frqgcd * dfrq;
            n2 = 1;
            n2y = n2x = 1;
            f2order = new int[n2y];
            f2order[0] = 0;
            f2inc = new int[n2y];
            f2inc[0] = sfrq / dfrq;
            stage2 = new double[n2y][n2x];
            stage2[0][0] = 1;
        } else {
            double aa = AA; // stop band attenuation(dB)
            double lpf, d, df, alp, iza;
//          double delta;
            double guard = 2;

            fs2 = sfrq / frqgcd * dfrq;

            df = (fs1 / 2 - sfrq / 2) * 2 / guard;
            lpf = sfrq / 2 + (fs1 / 2 - sfrq / 2) / guard;

//          delta = Math.pow(10, -aa / 20);
            if (aa <= 21) {
                d = 0.9222;
            } else {
                d = (aa - 7.95) / 14.36;
            }

            n2 = (int) (fs2 / df * d + 1);
            if (n2 % 2 == 0) {
                n2++;
            }

            alp = alpha(aa);
            iza = I0Bessel.value(alp);
//System.err.printf("iza %f, alp: %f\n", iza, alp); // OK

            n2y = fs2 / fs1; // 0Tvfs2Tv?H
            n2x = n2 / n2y + 1;

            f2order = new int[n2y];
            for (i = 0; i < n2y; i++) {
                f2order[i] = fs2 / fs1 - (i * (fs2 / dfrq)) % (fs2 / fs1);
                if (f2order[i] == fs2 / fs1) {
                    f2order[i] = 0;
                }
            }

            f2inc = new int[n2y];
            for (i = 0; i < n2y; i++) {
                f2inc[i] = (fs2 / dfrq - f2order[i]) / (fs2 / fs1) + 1;
                if (f2order[i + 1 == n2y ? 0 : i + 1] == 0) {
                    f2inc[i]--;
                }
            }

            stage2 = new double[n2y][n2x];

//System.err.printf("n2y: %d, n2: %d\n", n2y, n2);
            for (i = -(n2 / 2); i <= n2 / 2; i++) {
                stage2[(i + n2 / 2) % n2y][(i + n2 / 2) / n2y] = win(i, n2, alp, iza) * hn_lpf(i, lpf, fs2) * fs2 / fs1;
//System.err.printf(" stage2[%02d][%02d]: %f\n", (i + n2 / 2) % n2y, (i + n2 / 2) / n2y, win(i, n2, alp, iza) * hn_lpf(i, lpf, fs2) * fs2 / fs1); // OK
            }
        }

        // Apply filters

        setstarttime();

        {
            int n1b2 = n1b / 2;
            int rp; // inbuffs1Tv???
            int rps; // rp(fs1/sfrq=osf)]
            int rp2; // buf2fs2Tv???
            int ds; // disposesfrqTv?
            // ?t@Cinbuf?lvZ stage2 filternTv?
//          int nsmplwrt1;
            // ?t@Cinbuf?lvZ stage2 filternTv?
            int nsmplwrt2 = 0;
            int s2p; // stage1 filter?oTv?n1y*osf]
            boolean init, ending;
//          int osc;
            int bp; // rp2vZ?Dbuf2Tvu
            int rps_backup, s2p_backup;
            int k, ch, p;
            int inbuflen = 0;
            int sumread, sumwrite;
            int delay = 0;
            int op;

            // |....B....|....C....| buf1 n1b2+n1b2
            // |.A.|....D....| buf2 n2x+n1b2
            //
            // inbufBosf{TvORs?[
            // CNA
            // BCstage 1 filter
            // DB
            // ADstage 2 filter
            // DA
            // CDRs?[

            buf1 = new double[nch][n1b];                                      //rawoutbuf = calloc(nch*(n2b2/osf+1),dbps);

            buf2 = new double[nch][n2x + 1 + n1b2];

            rawinbuf = ByteBuffer.allocate((nch * (n1b2 / osf + osf + 1)) * bps);
//System.err.println((double) n1b2 * sfrq / dfrq + 1);
            rawoutbuf = ByteBuffer.allocate((int) (((double) n1b2 * dfrq / sfrq + 1) * (dbps * nch)));
            inbuf = new double[nch * (n1b2 / osf + osf + 1)];
            outbuf = new double[(int) (nch * ((double) n1b2 * dfrq / sfrq + 1))];

            op = 0; // outbuf

            s2p = 0;
            rp = 0;
            rps = 0;
            ds = 0;
//          osc = 0;
            rp2 = 0;

            init = true;
            ending = false;
            delay = (int) ((double) n1 / 2 / ((double) fs1 / dfrq) + (double) n2 / 2 / ((double) fs2 / dfrq));
            sumread = sumwrite = 0;

            while (true) {
                int nsmplread;
                int toberead;
                rps = 0;   //TODO settings this parameter to zero fixed a lot of problems
                toberead = (n1b2 - rps - 1) / osf + 1;
                if (toberead + sumread > chanklen) {
                    toberead = chanklen - sumread;
                }

                rawinbuf.position(0);
                rawinbuf.limit(bps * nch * toberead);

                byte[] tempData = new byte[rawinbuf.limit()];
                nsmplread = fpi.read(tempData);
                if (nsmplread < 0) {
                    nsmplread = 0;
                }

                //TODO sometimes happens, investigate around it
                if (nsmplread < rawinbuf.limit()) {
                    chanklen = sumread + nsmplread / bps * nch;
                }

                rawinbuf.limit(nsmplread);
                rawinbuf = ByteBuffer.wrap(tempData);
                rawinbuf.position(nsmplread);

                rawinbuf.flip();
                nsmplread /= bps * nch;

                switch (bps) {
                    case 1:
                        for (i = 0; i < nsmplread * nch; i++) {
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7f) * ((rawinbuf.get(i) & 0xff) - 128);
                        }
                        break;

                    case 2:
                        for (i = 0; i < nsmplread * nch; i++) {
                            int v = rawinbuf.order(byteOrder).asShortBuffer().get(i);
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fff) * v;
//                            System.err.printf("I: %f\n", inbuf[nch * inbuflen + i]);
                        }
                        break;

                    case 3:
                        for (i = 0; i < nsmplread * nch; i++) {
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fffff) *
                                    (((rawinbuf.get(i * 3) & 0xff) << 0) |
                                            ((rawinbuf.get(i * 3 + 1) & 0xff) << 8) |
                                            ((rawinbuf.get(i * 3 + 2) & 0xff) << 16));
                        }
                        break;

                    case 4:
                        for (i = 0; i < nsmplread * nch; i++) {
                            int v = rawinbuf.order(byteOrder).getInt(i);
                            inbuf[nch * inbuflen + i] = (1 / (double) 0x7fffffff) * v;
                        }
                        break;
                }

                for (; i < nch * toberead; i++) {
                    inbuf[i] = 0;
                }

                sumread += nsmplread;

//                ending = sumread >= chanklen;
                ending = fpi.available() < 0 || sumread >= chanklen;


                rps_backup = rps;
                s2p_backup = s2p;

                for (ch = 0; ch < nch; ch++) {
                    rps = rps_backup;

                    for (k = 0; k < rps; k++) {
                        buf1[ch][k] = 0;
                    }

                    for (i = rps, j = 0; i < n1b2; i += osf, j++) {
                        assert (j < ((n1b2 - rps - 1) / osf + 1));

                        buf1[ch][i] = inbuf[j * nch + ch];

                        for (k = i + 1; k < i + osf; k++) {
                            buf1[ch][k] = 0;
                        }
                    }

                    assert (j == ((n1b2 - rps - 1) / osf + 1));

                    for (k = n1b2; k < n1b; k++) {
                        buf1[ch][k] = 0;
                    }

                    rps = i - n1b2;
                    rp += j;

                    fft.rdft(n1b, 1, buf1[ch], fft_ip, fft_w);

                    buf1[ch][0] = stage1[0] * buf1[ch][0];
                    buf1[ch][1] = stage1[1] * buf1[ch][1];

                    for (i = 1; i < n1b2; i++) {
                        double re, im;

                        re = stage1[i * 2] * buf1[ch][i * 2] - stage1[i * 2 + 1] * buf1[ch][i * 2 + 1];
                        im = stage1[i * 2 + 1] * buf1[ch][i * 2] + stage1[i * 2] * buf1[ch][i * 2 + 1];

                        buf1[ch][i * 2] = re;
                        buf1[ch][i * 2 + 1] = im;
                    }

                    fft.rdft(n1b, -1, buf1[ch], fft_ip, fft_w);

                    for (i = 0; i < n1b2; i++) {
                        buf2[ch][n2x + 1 + i] += buf1[ch][i];
                    }

                    {
                        int t1 = rp2 / (fs2 / fs1);
                        if (rp2 % (fs2 / fs1) != 0) {
                            t1++;
                        }

                        bp = buf2[0].length * ch + t1; // &(buf2[ch][t1]);
                    }

                    s2p = s2p_backup;

                    for (p = 0; bp - (buf2[0].length * ch) < n1b2 + 1; p++) { // buf2[ch]
                        double tmp = 0;
                        int bp2;
                        int s2o;

                        bp2 = bp;
                        s2o = f2order[s2p];
                        bp += f2inc[s2p];
                        s2p++;

                        if (s2p == n2y) {
                            s2p = 0;
                        }

                        assert ((bp2 - (buf2[0].length * ch)) * (fs2 / fs1) - (rp2 + p * (fs2 / dfrq)) == s2o); // &(buf2[ch][0])
                        for (i = 0; i < n2x; i++) {
//System.err.printf("%d (%d, %d)\n", i, bp2 / buf2[0].length, bp2 % buf2[0].length);
                            tmp += stage2[s2o][i] * buf2[bp2 / buf2[0].length][bp2 % buf2[0].length]; // *bp2++
                            bp2++;
                        }

                        outbuf[op + p * nch + ch] = tmp;
//System.err.printf("O: %06d: %f\n", op + p * nch + ch, tmp);
                    }

                    nsmplwrt2 = p;
                }

                rp2 += nsmplwrt2 * (fs2 / dfrq);

                rawoutbuf.clear();
                if (twopass) {
                    for (i = 0; i < nsmplwrt2 * nch; i++) {
                        double f = outbuf[i] > 0 ? outbuf[i] : -outbuf[i];
                        peak[0] = peak[0] < f ? f : peak[0];
//System.err.println("p: " + rawoutbuf.position() + ", l: " + rawoutbuf.limit());
                        rawoutbuf.asDoubleBuffer().put(i, outbuf[i]);
//if (i < 100) {
// System.err.printf("1: %06d: %f\n", i, outbuf[i]);
//}
//System.err.print(StringUtil.getDump(rawoutbuf, i, 8));
                    }
                } else {
                    switch (dbps) {
                        case 1: {
                            double gain2 = gain * 0x7f;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x80) {
                                        double d = (double) s / -0x80;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x80;
                                    }
                                    if (0x7f < s) {
                                        double d = (double) s / 0x7f;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7f;
                                    }
                                }

                                rawoutbuf.put(i, (byte) (s + 0x80));

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                        case 2: {
                            double gain2 = gain * 0x7fff;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x8000) {
                                        double d = (double) s / -0x8000;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x8000;
                                    }
                                    if (0x7fff < s) {
                                        double d = (double) s / 0x7fff;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7fff;
                                    }
                                }

                                rawoutbuf.order(byteOrder).asShortBuffer().put(i, (short) s);

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                        case 3: {
                            double gain2 = gain * 0x7fffff;
                            ch = 0;

                            for (i = 0; i < nsmplwrt2 * nch; i++) {
                                int s;

                                if (dither != 0) {
                                    s = do_shaping(outbuf[i] * gain2, peak, dither, ch);
                                } else {
                                    s = RINT(outbuf[i] * gain2);

                                    if (s < -0x800000) {
                                        double d = (double) s / -0x800000;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = -0x800000;
                                    }
                                    if (0x7fffff < s) {
                                        double d = (double) s / 0x7fffff;
                                        peak[0] = peak[0] < d ? d : peak[0];
                                        s = 0x7fffff;
                                    }
                                }

                                rawoutbuf.put(i * 3, (byte) (s & 255));
                                s >>= 8;
                                rawoutbuf.put(i * 3 + 1, (byte) (s & 255));
                                s >>= 8;
                                rawoutbuf.put(i * 3 + 2, (byte) (s & 255));

                                ch++;
                                if (ch == nch) {
                                    ch = 0;
                                }
                            }
                        }
                        break;

                    }
                }

                if (!init) {
                    if (ending) {
                        if ((double) sumread * dfrq / sfrq + 2 > sumwrite + nsmplwrt2) {
                            rawoutbuf.position(0);
                            rawoutbuf.limit(dbps * nch * nsmplwrt2);
                            writeBuffers(fpo, rawoutbuf);
                            sumwrite += nsmplwrt2;
                        } else {
                            rawoutbuf.position(0);
                            int limitData = (int) (dbps * nch * (Math.floor((double) sumread * dfrq / sfrq) + 2 - sumwrite));
                            if (limitData > 0) {
                                rawoutbuf.limit(limitData);
                                writeBuffers(fpo, rawoutbuf);
                            }
                            break;
                        }
                    } else {
                        rawoutbuf.position(0);
                        rawoutbuf.limit(dbps * nch * nsmplwrt2);
                        writeBuffers(fpo, rawoutbuf);
                        sumwrite += nsmplwrt2;
                    }
                } else {
                    if (nsmplwrt2 < delay) {
                        delay -= nsmplwrt2;
                    } else {
                        if (ending) {
                            if ((double) sumread * dfrq / sfrq + 2 > sumwrite + nsmplwrt2 - delay) {
                                rawoutbuf.position(dbps * nch * delay);
                                rawoutbuf.limit(dbps * nch * (nsmplwrt2 - delay));
                                writeBuffers(fpo, rawoutbuf);
                                sumwrite += nsmplwrt2 - delay;
                            } else {
                                rawoutbuf.position(dbps * nch * delay);
                                rawoutbuf.limit((int) (dbps * nch * (Math.floor((double) sumread * dfrq / sfrq) + 2 + sumwrite + nsmplwrt2 - delay)));  //TODO fails with short signals (think that fixed this)
                                writeBuffers(fpo, rawoutbuf);
                                break;
                            }
                        } else {
                            rawoutbuf.position(dbps * nch * delay);
                            rawoutbuf.limit(dbps * nch * (nsmplwrt2));
                            writeBuffers(fpo, rawoutbuf);
                            sumwrite += nsmplwrt2 - delay;
                            init = false;
                        }
                    }
                }

                {
                    ds = (rp2 - 1) / (fs2 / fs1);

                    if (ds > n1b2) {
                        ds = n1b2;
                    }

                    for (ch = 0; ch < nch; ch++) {
                        System.arraycopy(buf2[ch], ds, buf2[ch], 0, n2x + 1 + n1b2 - ds); // memmove TODO overlap
                    }

                    rp2 -= ds * (fs2 / fs1);
                }

                for (ch = 0; ch < nch; ch++) {
                    System.arraycopy(buf1[ch], n1b2, buf2[ch], n2x + 1, n1b2);
                }

                if ((spcount++ & 7) == 7) {
                    showprogress((double) sumread / chanklen);
                }
            }
        }

        showprogress(1);

        return peak[0];
    }

    /** */
    public double no_src(InputStream fpi, OutputStream fpo, int nch, int bps, int dbps, double gain, int chanklen, boolean twopass, int dither) throws IOException {
        double[] peak = new double[]{
                0
        };
        int ch = 0, sumread = 0;

        setstarttime();

        ByteBuffer leos = null;
        if (twopass) {
            leos = ByteBuffer.allocate(8);
        }

        ByteBuffer buf = ByteBuffer.allocate(4);
        while (sumread < chanklen * nch) {
            double f = 0;
            int s;

            switch (bps) {
                case 1:
                    buf.position(0);
                    buf.limit(1);

                    byte[] tempData = new byte[buf.limit()];
                    fpi.read(tempData);
                    buf = ByteBuffer.wrap(tempData);
                    buf.position(buf.limit());


                    buf.flip();
                    f = (1 / (double) 0x7f) * (buf.get(0) - 128);
                    break;
                case 2:
                    buf.position(0);
                    buf.limit(2);

                    tempData = new byte[buf.limit()];
                    fpi.read(tempData);
                    buf = ByteBuffer.wrap(tempData);
                    buf.position(buf.limit());

                    buf.flip();
                    s = buf.order(byteOrder).asShortBuffer().get(0);
                    f = (1 / (double) 0x7fff) * s;
                    break;
                case 3:
                    buf.position(0);
                    buf.limit(3);

                    tempData = new byte[buf.limit()];
                    fpi.read(tempData);
                    buf = ByteBuffer.wrap(tempData);
                    buf.position(buf.limit());

                    buf.flip();
                    f = (1 / (double) 0x7fffff) *
                            (((buf.get(0) & 0xff) << 0) |
                                    ((buf.get(1) & 0xff) << 8) |
                                    ((buf.get(2) & 0xff) << 16));
                    break;
                case 4:
                    buf.position(0);
                    buf.limit(4);

                    tempData = new byte[buf.limit()];
                    fpi.read(tempData);
                    buf = ByteBuffer.wrap(tempData);
                    buf.position(buf.limit());

                    buf.flip();
                    s = buf.order(byteOrder).asIntBuffer().get(0);
                    f = (1 / (double) 0x7fffffff) * s;
                    break;
            }
            ;

            if (fpi.available() == 0) {
//            if (fpi.position() == fpi.size()) {
                break;
            }
            f *= gain;

            if (!twopass) {
                switch (dbps) {
                    case 1:
                        f *= 0x7f;
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);
                        buf.position(0);
                        buf.limit(1);
                        buf.put(0, (byte) (s + 128));
                        buf.flip();
                        writeBuffers(fpo, buf);
                        break;
                    case 2:
                        f *= 0x7fff;
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);
                        buf.position(0);
                        buf.limit(2);
                        buf.asShortBuffer().put(0, (short) s);
                        buf.flip();
                        writeBuffers(fpo, buf);
                        break;
                    case 3:
                        f *= 0x7fffff;
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);
                        buf.position(0);
                        buf.limit(3);
                        buf.put(0, (byte) (s & 255));
                        s >>= 8;
                        buf.put(1, (byte) (s & 255));
                        s >>= 8;
                        buf.put(2, (byte) (s & 255));
                        buf.flip();
                        writeBuffers(fpo, buf);
                        break;
                }
            } else {
                double p = f > 0 ? f : -f;
                peak[0] = peak[0] < p ? p : peak[0];
                leos.position(0);
                leos.putDouble(f);
                leos.flip();
                writeBuffers(fpo, leos);
            }

            ch++;
            if (ch == nch) {
                ch = 0;
            }
            sumread++;

            if ((sumread & 0x3ffff) == 0) {
                showprogress((double) sumread / (chanklen * nch));
            }
        }

        showprogress(1);

        return peak[0];
    }

    /** */
    public static void main(String[] args) throws Exception {
        new SSRC(args);
    }

    /** */
    private static final double presets[] = {
            0.7, 0.9, 0.18
    };

    public SSRC(){}

    /** */
    SSRC(String[] argv) throws IOException {
        String sfn, dfn, tmpfn = null;
        FileInputStream fpi = null;
        File fo = null;
        FileOutputStream fpo = null;
        File ft = null;
        FileOutputStream fpto = null;
        boolean twopass, normalize;
        int dither, pdf, samp = 0;
        int nch, bps;
        int length;
        int sfrq, dfrq, dbps;
        double att, noiseamp;
        double[] peak = new double[]{0};
        int i;

        // parse command line options

        dfrq = -1;
        att = 0;
        dbps = -1;
        twopass = false;
        normalize = false;
        dither = 0;
        pdf = 0;
        noiseamp = 0.18;

        for (i = 0; i < argv.length; i++) {
            if (argv[i].charAt(0) != '-') {
                break;
            }

            if (argv[i].equals("--rate")) {
                dfrq = Integer.parseInt(argv[++i]);
//System.err.printf("dfrq: %d\n", dfrq);
                continue;
            }

            if (argv[i].equals("--att")) {
                att = Float.parseFloat(argv[++i]);
                continue;
            }

            if (argv[i].equals("--bits")) {
                dbps = Integer.parseInt(argv[++i]);
                if (dbps != 8 && dbps != 16 && dbps != 24) {
                    throw new IllegalArgumentException("Error: Only 8bit, 16bit and 24bit PCM are supported.");
                }
                dbps /= 8;
                continue;
            }

            if (argv[i].equals("--twopass")) {
                twopass = true;
                continue;
            }

            if (argv[i].equals("--normalize")) {
                twopass = true;
                normalize = true;
                continue;
            }

            if (argv[i].equals("--dither")) {
                try {
                    dither = Integer.parseInt(argv[i + 1]);
                    if (dither < 0 || dither > 4) {
                        throw new IllegalArgumentException("unrecognized dither type : " + argv[i + 1]);
                    }
                    i++;
                } catch (NumberFormatException e) {
                    dither = -1;
                }
                continue;
            }

            if (argv[i].equals("--pdf")) {
                try {
                    pdf = Integer.parseInt(argv[i + 1]);
                    if (pdf < 0 || pdf > 2) {
                        throw new IllegalArgumentException("unrecognized p.d.f. type : " + argv[i + 1]);
                    }
                    i++;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("unrecognized p.d.f. type : " + argv[i + 1]);
                }

                try {
                    noiseamp = Double.parseDouble(argv[i + 1]);
                    i++;
                } catch (NumberFormatException e) {
                    noiseamp = presets[pdf];
                }

                continue;
            }

            if (argv[i].equals("--quiet")) {
                quiet = true;
                continue;
            }

            if (argv[i].equals("--tmpfile")) {
                tmpfn = argv[++i];
                continue;
            }

            if (argv[i].equals("--profile")) {
                if (argv[i + 1].equals("fast")) {
                    AA = 96;
                    DF = 8000;
                    FFTFIRLEN = 1024;
                } else if (argv[i + 1].equals("standard")) {
                    /* nothing to do */
                } else {
                    throw new IllegalArgumentException("unrecognized profile : " + argv[i + 1]);
                }
                i++;
                continue;
            }

            throw new IllegalArgumentException("unrecognized option : " + argv[i]);
        }

        if (!quiet) {
            System.err.printf("Shibatch sampling rate converter version " + VERSION + "(high precision/nio)\n\n");
        }

        if (argv.length - i != 2) {
            usage();
            throw new IllegalStateException("too few arguments");
        }

        sfn = argv[i];
        dfn = argv[i + 1];

        try {
            fpi = new FileInputStream(sfn);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot open input file.");
        }

        // read wav header

        {
            @SuppressWarnings("unused")
            short word;
            @SuppressWarnings("unused")
            int dword;

            ByteBuffer bb = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
            bb.limit(36);
            fpi.getChannel().read(bb);
            bb.flip();
            System.err.println("p: " + bb.position() + ", l: " + bb.limit());
            if (bb.get() != 'R') fmterr(1);
            if (bb.get() != 'I') fmterr(1);
            if (bb.get() != 'F') fmterr(1);
            if (bb.get() != 'F') fmterr(1);

            dword = bb.getInt();

            if (bb.get() != 'W') fmterr(2);
            if (bb.get() != 'A') fmterr(2);
            if (bb.get() != 'V') fmterr(2);
            if (bb.get() != 'E') fmterr(2);
            if (bb.get() != 'f') fmterr(2);
            if (bb.get() != 'm') fmterr(2);
            if (bb.get() != 't') fmterr(2);
            if (bb.get() != ' ') fmterr(2);

            int sizeOfFmt = bb.getInt();

            if (bb.getShort() != 1) {
                throw new IllegalStateException("Error: Only PCM is supported.");
            }
            nch = bb.getShort();
            sfrq = bb.getInt();
            bps = bb.getInt();
            if (bps % sfrq * nch != 0) {
                fmterr(4);
            }

            word = bb.getShort();
            word = bb.getShort();

            bps /= sfrq * nch;

            if (sizeOfFmt > 16) {
                bb.position(0);
                bb.limit(2);
                fpi.read(getDataFromByteBuffer(bb));
                bb.flip();
                int sizeofExtended = bb.getShort();
                fpi.getChannel().position(fpi.getChannel().position() + sizeofExtended);
            }

            while (true) {
                bb.position(0);
                bb.limit(8);
                fpi.getChannel().read(bb);
                bb.flip();
                int c0 = bb.get();
                int c1 = bb.get();
                int c2 = bb.get();
                int c3 = bb.get();
                length = bb.getInt();
                System.err.printf("chunk: %c%c%c%c\n", c0, c1, c2, c3);
                if (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'a') {
                    break;
                }
                if (fpi.getChannel().position() == fpi.getChannel().size()) {
                    break;
                }
                fpi.getChannel().position(fpi.getChannel().position() + length);
            }
            if (fpi.getChannel().position() == fpi.getChannel().size()) {
                throw new IllegalStateException("Couldn't find data chank");
            }
        }

        if (bps != 1 && bps != 2 && bps != 3 && bps != 4) {
            throw new IllegalStateException("Error : Only 8bit, 16bit, 24bit and 32bit PCM are supported.");
        }

        if (dbps == -1) {
            if (bps != 1) {
                dbps = bps;
            } else {
                dbps = 2;
            }
            if (dbps == 4) {
                dbps = 3;
            }
        }

        if (dfrq == -1) {
            dfrq = sfrq;
        }

        if (dither == -1) {
            if (dbps < bps) {
                if (dbps == 1) {
                    dither = 4;
                } else {
                    dither = 3;
                }
            } else {
                dither = 1;
            }
        }

        if (!quiet) {
            final String[] dtype = {
                    "none", "no noise shaping", "triangular spectral shape", "ATH based noise shaping", "ATH based noise shaping(less amplitude)"
            };
            final String[] ptype = {
                    "rectangular", "triangular", "gaussian"
            };
            System.err.printf("frequency : %d -> %d\n", sfrq, dfrq);
            System.err.printf("attenuation : %gdB\n", att);
            System.err.printf("bits per sample : %d -> %d\n", bps * 8, dbps * 8);
            System.err.printf("nchannels : %d\n", nch);
            System.err.printf("length : %d bytes, %g secs\n", length, (double) length / bps / nch / sfrq);
            if (dither == 0) {
                System.err.printf("dither type : none\n");
            } else {
                System.err.printf("dither type : %s, %s p.d.f, amp = %g\n", dtype[dither], ptype[pdf], noiseamp);
            }
            System.err.printf("\n");
        }

        if (twopass) {
        }

        try {
            fo = new File(dfn);
            fpo = new FileOutputStream(fo);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot open output file.");
        }

        // generate wav header

        {
            short word;
            int dword;

            ByteBuffer leos = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

            leos.put("RIFF".getBytes());
            dword = 0;
            leos.putInt(dword);

            leos.put("WAVEfmt ".getBytes());
            dword = 16;
            leos.putInt(dword);
            word = 1;
            leos.putShort(word); // inAudioFormat category, PCM
            word = (short) nch;
            leos.putShort(word); // channels
            dword = dfrq;
            leos.putInt(dword); // sampling rate
            dword = dfrq * nch * dbps;
            leos.putInt(dword); // bytes per sec
            word = (short) (dbps * nch);
            leos.putShort(word); // block alignment
            word = (short) (dbps * 8);
            leos.putShort(word); // bits per sample

            leos.put("data".getBytes());
            dword = 0;
            leos.putInt(dword);

            leos.flip();
            writeBuffers(fpo, leos);
        }

        if (dither != 0) {
            int min = 0, max = 0;
            if (dbps == 1) {
                min = -0x80;
                max = 0x7f;
            }
            if (dbps == 2) {
                min = -0x8000;
                max = 0x7fff;
            }
            if (dbps == 3) {
                min = -0x800000;
                max = 0x7fffff;
            }
            if (dbps == 4) {
                min = -0x80000000;
                max = 0x7fffffff;
            }

            samp = init_shaper(dfrq, nch, min, max, dither, pdf, noiseamp);
        }

        if (twopass) {
            double gain = 0;
            int ch = 0;
            int fptlen, sumread;

            if (!quiet) {
                System.err.printf("Pass 1\n");
            }

            try {
                if (tmpfn != null) {
                    ft = new File(tmpfn);
                } else {
                    ft = File.createTempFile("ssrc_", ".tmp");
                }
                fpto = new FileOutputStream(ft);
            } catch (IOException e) {
                throw new IllegalStateException("cannot open temporary file.");
            }

//System.err.printf("nch: %d, bps: %d, size: %d, sfrq: %d, dfrq: %d, ???: %d, ???: %d, twopass: %b, dither: %d\n", nch, bps, 8, sfrq, dfrq, 1, length / bps / nch, twopass, dither);
            if (normalize) {
                if (sfrq < dfrq) {
                    peak[0] = upsample(fpi, fpto, nch, bps, 8, sfrq, dfrq, 1, length / bps / nch, twopass, dither);
                } else if (sfrq > dfrq) {
                    peak[0] = downsample(fpi, fpto, nch, bps, 8, sfrq, dfrq, 1, length / bps / nch, twopass, dither);
                } else {
                    peak[0] = no_src(fpi, fpto, nch, bps, 8, 1, length / bps / nch, twopass, dither);
                }
            } else {
                if (sfrq < dfrq) {
                    peak[0] = upsample(fpi, fpto, nch, bps, 8, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
                } else if (sfrq > dfrq) {
                    peak[0] = downsample(fpi, fpto, nch, bps, 8, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
                } else {
                    peak[0] = no_src(fpi, fpto, nch, bps, 8, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
                }
            }

            fpto.close();

            if (!quiet) {
                System.err.printf("\npeak : %gdB\n", 20 * Math.log10(peak[0]));
            }

            if (!normalize) {
                if (peak[0] < Math.pow(10, -att / 20)) {
                    peak[0] = 1;
                } else {
                    peak[0] *= Math.pow(10, att / 20);
                }
            } else {
                peak[0] *= Math.pow(10, att / 20);
            }

            if (!quiet) {
                System.err.printf("\nPass 2\n");
            }

            if (dither != 0) {
                switch (dbps) {
                    case 1:
                        gain = (normalize || peak[0] >= (0x7f - samp) / (double) 0x7f) ? 1 / peak[0] * (0x7f - samp) : 1 / peak[0] * 0x7f;
                        break;
                    case 2:
                        gain = (normalize || peak[0] >= (0x7fff - samp) / (double) 0x7fff) ? 1 / peak[0] * (0x7fff - samp) : 1 / peak[0] * 0x7fff;
                        break;
                    case 3:
                        gain = (normalize || peak[0] >= (0x7fffff - samp) / (double) 0x7fffff) ? 1 / peak[0] * (0x7fffff - samp) : 1 / peak[0] * 0x7fffff;
                        break;
                }
            } else {
                switch (dbps) {
                    case 1:
                        gain = 1 / peak[0] * 0x7f;
                        break;
                    case 2:
                        gain = 1 / peak[0] * 0x7fff;
                        break;
                    case 3:
                        gain = 1 / peak[0] * 0x7fffff;
                        break;
                }
            }
            randptr = 0;

            setstarttime();

            fptlen = (int) (ft.length() / 8);
//System.err.println("tmp: " + fpt.getFilePointer());

            FileChannel fpti = new FileInputStream(ft).getChannel();
            ByteBuffer leis = ByteBuffer.allocate(8);
            for (sumread = 0; sumread < fptlen; ) {
                double f;
                int s;

                leis.clear();
                fpti.read(leis);
                leis.flip();
                f = leis.getDouble();
//if (sumread < 100) {
// System.err.printf("2: %06d: %f\n", sumread, f);
//}
                f *= gain;
                sumread++;

                switch (dbps) {
                    case 1: {
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);

                        ByteBuffer buf = ByteBuffer.allocate(1);
                        buf.put((byte) (s + 128));
                        buf.flip();

                        writeBuffers(fpo, buf);
                    }
                    break;
                    case 2: {
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);

                        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
                        buf.putShort((short) s);
                        buf.flip();

                        writeBuffers(fpo, buf);
                    }
                    break;
                    case 3: {
                        s = dither != 0 ? do_shaping(f, peak, dither, ch) : RINT(f);

                        ByteBuffer buf = ByteBuffer.allocate(3);
                        buf.put((byte) (s & 255));
                        s >>= 8;
                        buf.put((byte) (s & 255));
                        s >>= 8;
                        buf.put((byte) (s & 255));
                        buf.flip();

                        writeBuffers(fpo, buf);
                    }
                    break;
                }

                ch++;
                if (ch == nch) {
                    ch = 0;
                }

                if ((sumread & 0x3ffff) == 0) {
                    showprogress((double) sumread / fptlen);
                }
            }
            showprogress(1);
            if (!quiet) {
                System.err.printf("\n");
            }
            fpti.close();
            if (ft != null) {
//System.err.println("ft: " + ft);
                if (ft.delete() == false) {
                    System.err.printf("Failed to remove %s\n", ft);
                }
            }
        } else {
            if (sfrq < dfrq) {
                peak[0] = upsample(fpi, fpo, nch, bps, dbps, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
            } else if (sfrq > dfrq) {
                peak[0] = downsample(fpi, fpo, nch, bps, dbps, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
            } else {
                peak[0] = no_src(fpi, fpo, nch, bps, dbps, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
            }
            if (!quiet) {
                System.err.printf("\n");
            }
        }

        if (dither != 0) {
            quit_shaper(nch);
        }

        if (!twopass && peak[0] > 1) {
            if (!quiet) {
                System.err.printf("clipping detected : %gdB\n", 20 * Math.log10(peak[0]));
            }
        }

        {
            int dword;
            int len;

            fpo.close();

            fo = new File(dfn);

            len = (int) fo.length();
            FileChannel fpo1 = new RandomAccessFile(fo, "rw").getChannel();
            ByteBuffer leos = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

            dword = len - 8;
            leos.position(0);
            leos.limit(4);
            leos.putInt(dword);
            leos.flip();
            fpo1.write(leos, 4);

            dword = len - 44;
            leos.position(0);
            leos.limit(4);
            leos.putInt(dword);
            leos.flip();
            fpo1.write(leos, 40);

            fpo1.close();
        }
    }


    /** */
    public SSRC(InputStream fpi, OutputStream fpo, int sfrq, int dfrq, int bps, int dbps, int nch, int length, double att, int dither, boolean quiet_) throws IOException {
        String tmpfn = null;
        boolean twopass, normalize;
        int pdf, samp = 0;
        double noiseamp;
        double[] peak = new double[]{0};
        int i;

        // parse command line options

        twopass = false;
        normalize = false;

        pdf = 0;
        noiseamp = 0.18;


        if (dither < 0 || dither > 4) {
            throw new IllegalArgumentException("unrecognized dither type : " + dither);
        }
        this.quiet = quiet_;


        if (!quiet) {
            System.err.printf("Shibatch sampling rate converter version " + VERSION + "(high precision/nio)\n\n");
        }

        if (bps != 1 && bps != 2 && bps != 3 && bps != 4) {
            throw new IllegalStateException("Error : Only 8bit, 16bit, 24bit and 32bit PCM are supported.");
        }

        if (dbps == -1) {
            if (bps != 1) {
                dbps = bps;
            } else {
                dbps = 2;
            }
            if (dbps == 4) {
                dbps = 3;
            }
        }

        if (dfrq == -1) {
            dfrq = sfrq;
        }

        if (dither == -1) {
            if (dbps < bps) {
                if (dbps == 1) {
                    dither = 4;
                } else {
                    dither = 3;
                }
            } else {
                dither = 1;
            }
        }

        if (!quiet) {
            final String[] dtype = {
                    "none", "no noise shaping", "triangular spectral shape", "ATH based noise shaping", "ATH based noise shaping(less amplitude)"
            };
            final String[] ptype = {
                    "rectangular", "triangular", "gaussian"
            };
            System.err.printf("frequency : %d -> %d\n", sfrq, dfrq);
            System.err.printf("attenuation : %gdB\n", att);
            System.err.printf("bits per sample : %d -> %d\n", bps * 8, dbps * 8);
            System.err.printf("nchannels : %d\n", nch);
            System.err.printf("length : %d bytes, %g secs\n", length, (double) length / bps / nch / sfrq);
            if (dither == 0) {
                System.err.printf("dither type : none\n");
            } else {
                System.err.printf("dither type : %s, %s p.d.f, amp = %g\n", dtype[dither], ptype[pdf], noiseamp);
            }
            System.err.printf("\n");
        }


        if (dither != 0) {
            int min = 0, max = 0;
            if (dbps == 1) {
                min = -0x80;
                max = 0x7f;
            }
            if (dbps == 2) {
                min = -0x8000;
                max = 0x7fff;
            }
            if (dbps == 3) {
                min = -0x800000;
                max = 0x7fffff;
            }
            if (dbps == 4) {
                min = -0x80000000;
                max = 0x7fffffff;
            }

            samp = init_shaper(dfrq, nch, min, max, dither, pdf, noiseamp);
        }

        if (sfrq < dfrq) {
            peak[0] = upsample(fpi, fpo, nch, bps, dbps, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
        } else if (sfrq > dfrq) {
            peak[0] = downsample(fpi, fpo, nch, bps, dbps, sfrq, dfrq, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
        } else {
            peak[0] = no_src(fpi, fpo, nch, bps, dbps, Math.pow(10, -att / 20), length / bps / nch, twopass, dither);
        }
        if (!quiet) {
            System.err.printf("\n");
        }

        if (dither != 0) {
            quit_shaper(nch);
        }

        if (!twopass && peak[0] > 1) {
            if (!quiet) {
                System.err.printf("clipping detected : %gdB\n", 20 * Math.log10(peak[0]));
            }
        }

    }


    protected byte[] getDataFromByteBuffer(ByteBuffer rawoutbuf) {
        byte[] tempDataWrt = new byte[rawoutbuf.limit() - rawoutbuf.position()];
        rawoutbuf.get(tempDataWrt, 0, tempDataWrt.length);

        return tempDataWrt;
    }


    protected void writeBuffers(OutputStream fpo, ByteBuffer rawoutbuf) {
        try {
            fpo.write(getDataFromByteBuffer(rawoutbuf));
        } catch (IOException e) {
            // Some problems (Read end dead)
        }
    }


}

/* */
