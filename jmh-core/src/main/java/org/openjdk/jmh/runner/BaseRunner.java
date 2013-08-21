/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.runner;

import org.openjdk.jmh.logic.results.IterationData;
import org.openjdk.jmh.logic.results.Result;
import org.openjdk.jmh.logic.results.internal.RunResult;
import org.openjdk.jmh.output.format.OutputFormat;
import org.openjdk.jmh.runner.options.BaseOptions;
import org.openjdk.jmh.runner.parameters.IterationParams;
import org.openjdk.jmh.runner.parameters.MicroBenchmarkParameters;
import org.openjdk.jmh.runner.parameters.MicroBenchmarkParametersFactory;
import org.openjdk.jmh.runner.parameters.ThreadIterationParams;
import org.openjdk.jmh.util.ClassUtils;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * abstract runner - base class for Runner & ForkedRunner
 *
 * @author sergey.kuksenko@oracle.com
 */

public abstract class BaseRunner {

    /** Class holding all our runtime options/arguments */
    private final BaseOptions options;

    protected final OutputFormat out;

    public BaseRunner(BaseOptions options, OutputFormat handler) {
        this.options = options;
        this.out = handler;
    }

    /**
     * Run a micro benchmark in classic execution mode
     * The method manages both warmup and measurements iterations
     * @param benchmark benchmark to run
     */
    void runClassicBenchmark(BenchmarkRecord benchmark) {
        List<IterationData> allResults = new ArrayList<IterationData>();
        try {
            Class<?> clazz = ClassUtils.loadClass(benchmark.generatedClass());
            Method method = MicroBenchmarkHandlers.findBenchmarkMethod(clazz, benchmark.generatedMethod());

            MicroBenchmarkParameters executionParams = MicroBenchmarkParametersFactory.makeParams(options, benchmark, method);
            MicroBenchmarkHandler handler = MicroBenchmarkHandlers.getInstance(out, benchmark, clazz, method, executionParams, options);

            out.startBenchmark(handler.getBenchmark(), executionParams, this.options.isVerbose());

            // execute the appropriate number of warmup iterations
            // before the corresponding measurement interations.

            IterationParams warmupParams = executionParams.getWarmup();
            for (int i = 1; i <= warmupParams.getCount(); i++) {
                // will run system gc if we should
                if (runSystemGC()) {
                    out.verbosePrintln("System.gc() executed");
                }

                out.warmupIteration(handler.getBenchmark(), i, executionParams.getThreads(), warmupParams.getTime());
                boolean isLastIteration = false; // warmup is never the last iteration
                IterationData iterData = handler.runIteration(executionParams.getThreads(), warmupParams.getTime(), isLastIteration).setWarmup();
                out.warmupIterationResult(handler.getBenchmark(), i, options.getThreads(), iterData.getAggregatedResult());
            }

            IterationParams measureParams = executionParams.getIteration();
            for (int i = 1; i <= measureParams.getCount(); i++) {
                // will run system gc if we should
                if (runSystemGC()) {
                    out.verbosePrintln("System.gc() executed");
                }

                // run benchmark iteration
                out.iteration(handler.getBenchmark(), i, executionParams.getThreads(), measureParams.getTime());

                boolean isLastIteration = (i == measureParams.getCount());
                IterationData iterData = handler.runIteration(executionParams.getThreads(), measureParams.getTime(), isLastIteration);

                // might get an exception above, in which case the results list will be empty
                if (iterData.isResultsEmpty()) {
                    out.println("WARNING: No results returned, benchmark payload threw exception?");
                } else {
                    out.iterationResult(handler.getBenchmark(), i, executionParams.getThreads(), iterData.getAggregatedResult(), iterData.getProfilerResults());

                    if (options.shouldOutputDetailedResults()) {
                        out.detailedResults(handler.getBenchmark(), i, executionParams.getThreads(), iterData.getAggregatedResult());
                    }

                    allResults.add(iterData);
                }
            }

            // only print end-of-run output if we have actual results
            if (!allResults.isEmpty()) {
                RunResult result = aggregateIterationData(allResults);
                out.endBenchmark(handler.getBenchmark(), result);
            }

            handler.shutdown();

        } catch (Throwable ex) {
            out.exception(ex);
            if (this.options.shouldFailOnError()) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        }
    }


    /**
     * Execute System.gc() if we the System.gc option is set.
     *
     * @return true if we did
     */
    public boolean runSystemGC() {
        if (options.shouldDoGC()) {
            List<GarbageCollectorMXBean> enabledBeans = new ArrayList<GarbageCollectorMXBean>();

            long beforeGcCount = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = bean.getCollectionCount();
                if (count != -1) {
                    enabledBeans.add(bean);
                }
            }

            for (GarbageCollectorMXBean bean : enabledBeans) {
                beforeGcCount += bean.getCollectionCount();
            }

            // this call is asynchronous, should check whether it completes
            System.gc();

            final int MAX_WAIT_MSEC = 20 * 1000;

            if (enabledBeans.isEmpty()) {
                out.println("WARNING: MXBeans can not report GC info. System.gc() invoked, pessimistically waiting " + MAX_WAIT_MSEC + " msecs");
                try {
                    TimeUnit.MILLISECONDS.sleep(MAX_WAIT_MSEC);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            long start = System.nanoTime();
            while (System.nanoTime() - start < MAX_WAIT_MSEC) {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                long afterGcCount = 0;
                for (GarbageCollectorMXBean bean : enabledBeans) {
                    afterGcCount += bean.getCollectionCount();
                }

                if (afterGcCount > beforeGcCount) {
                    return true;
                }
            }

            out.println("WARNING: System.gc() was invoked but couldn't detect a GC occuring, is System.gc() disabled?");
            return false;
        }
        return false;
    }

    protected static RunResult aggregateIterationData(List<IterationData> results) {
        List<Result> res = new ArrayList<Result>(results.size());
        for (IterationData itData : results) {
            res.addAll(itData.getAggregatedResult().getResult().values());
        }
        return new RunResult(res);
    }

}
