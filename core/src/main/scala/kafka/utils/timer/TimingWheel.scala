/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.utils.timer

import kafka.utils.nonthreadsafe

import java.util.concurrent.DelayQueue
import java.util.concurrent.atomic.AtomicInteger

/*
 * Hierarchical Timing Wheels
 *
 * A simple timing wheel is a circular list of buckets of timer tasks. Let u be the time unit.
 * A timing wheel with size n has n buckets and can hold timer tasks in n * u time interval.
 * Each bucket holds timer tasks that fall into the corresponding time range. At the beginning,
 * the first bucket holds tasks for [0, u), the second bucket holds tasks for [u, 2u), …,
 * the n-th bucket for [u * (n -1), u * n). Every interval of time unit u, the timer ticks and
 * moved to the next bucket then expire all timer tasks in it. So, the timer never insert a task
 * into the bucket for the current time since it is already expired. The timer immediately runs
 * the expired task. The emptied bucket is then available for the next round, so if the current
 * bucket is for the time t, it becomes the bucket for [t + u * n, t + (n + 1) * u) after a tick.
 * A timing wheel has O(1) cost for insert/delete (start-timer/stop-timer) whereas priority queue
 * based timers, such as java.util.concurrent.DelayQueue and java.util.Timer, have O(log n)
 * insert/delete cost.
 *
 * A major drawback of a simple timing wheel is that it assumes that a timer request is within
 * the time interval of n * u from the current time. If a timer request is out of this interval,
 * it is an overflow. A hierarchical timing wheel deals with such overflows. It is a hierarchically
 * organized timing wheels. The lowest level has the finest time resolution. As moving up the
 * hierarchy, time resolutions become coarser. If the resolution of a wheel at one level is u and
 * the size is n, the resolution of the next level should be n * u. At each level overflows are
 * delegated to the wheel in one level higher. When the wheel in the higher level ticks, it reinsert
 * timer tasks to the lower level. An overflow wheel can be created on-demand. When a bucket in an
 * overflow bucket expires, all tasks in it are reinserted into the timer recursively. The tasks
 * are then moved to the finer grain wheels or be executed. The insert (start-timer) cost is O(m)
 * where m is the number of wheels, which is usually very small compared to the number of requests
 * in the system, and the delete (stop-timer) cost is still O(1).
 *
 * Example
 * Let's say that u is 1 and n is 3. If the start time is c,
 * then the buckets at different levels are:
 *
 * level    buckets
 * 1        [c,c]   [c+1,c+1]  [c+2,c+2]
 * 2        [c,c+2] [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8] [c+9,c+17] [c+18,c+26]
 *
 * The bucket expiration is at the time of bucket beginning.
 * So at time = c+1, buckets [c,c], [c,c+2] and [c,c+8] are expired.
 * Level 1's clock moves to c+1, and [c+3,c+3] is created.
 * Level 2 and level3's clock stay at c since their clocks move in unit of 3 and 9, respectively.
 * So, no new buckets are created in level 2 and 3.
 *
 * Note that bucket [c,c+2] in level 2 won't receive any task since that range is already covered in level 1.
 * The same is true for the bucket [c,c+8] in level 3 since its range is covered in level 2.
 * This is a bit wasteful, but simplifies the implementation.
 *
 * 1        [c+1,c+1]  [c+2,c+2]  [c+3,c+3]
 * 2        [c,c+2]    [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8]    [c+9,c+17] [c+18,c+26]
 *
 * At time = c+2, [c+1,c+1] is newly expired.
 * Level 1 moves to c+2, and [c+4,c+4] is created,
 *
 * 1        [c+2,c+2]  [c+3,c+3]  [c+4,c+4]
 * 2        [c,c+2]    [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8]    [c+9,c+17] [c+18,c+18]
 *
 * At time = c+3, [c+2,c+2] is newly expired.
 * Level 2 moves to c+3, and [c+5,c+5] and [c+9,c+11] are created.
 * Level 3 stay at c.
 *
 * 1        [c+3,c+3]  [c+4,c+4]  [c+5,c+5]
 * 2        [c+3,c+5]  [c+6,c+8]  [c+9,c+11]
 * 3        [c,c+8]    [c+9,c+17] [c+8,c+11]
 *
 * The hierarchical timing wheels works especially well when operations are completed before they time out.
 * Even when everything times out, it still has advantageous when there are many items in the timer.
 * Its insert cost (including reinsert) and delete cost are O(m) and O(1), respectively while priority
 * queue based timers takes O(log N) for both insert and delete where N is the number of items in the queue.
 *
 * This class is not thread-safe. There should not be any add calls while advanceClock is executing.
 * It is caller's responsibility to enforce it. Simultaneous add calls are thread-safe.
 */
@nonthreadsafe

/**
  * 时间轮，采用定长数组记录放置时间格。
  */
private[timer] class TimingWheel(tickMs: Long // 当前时间轮中一格的时间跨度
                                 , wheelSize: Int, // 时间轮的格数
                                 startMs: Long, // 当前时间轮的创建时间
                                 taskCounter: AtomicInteger, // 各层级时间轮共用的任务计数器，用于记录时间轮中总的任务数
                                 queue: DelayQueue[TimerTaskList]) { // 各个层级时间轮共用一个任务队列
  /**
    * 当前时间轮的时间跨度，
    * 只能处理时间范围在 [currentTime, currentTime + interval] 之间的延时任务，超过该范围则需要将任务添加到上层时间轮中
    */
  private[this] val interval = tickMs * wheelSize
  /**
    * 每一项都对应时间轮中的一格
    */
  private[this] val buckets = Array.tabulate[TimerTaskList](wheelSize) { _ => new TimerTaskList(taskCounter) }
  /**
    * 时间轮指针，将时间轮划分为到期部分和未到期部分
    */
  private[this] var currentTime = startMs - (startMs % tickMs) // rounding down to multiple of tickMs 修剪成 tickMs 的倍数，近似等于创建时间

  // overflowWheel can potentially be updated and read by two concurrent threads through add().
  // Therefore, it needs to be volatile due to the issue of Double-Checked Locking pattern with JVM
  /**
    * 对于上层时间轮的引用
    */
  @volatile private[this] var overflowWheel: TimingWheel = null

  /**
    * 添加并初始化上层时间轮，在 kafka 的分层时间轮算法设计中，上层时间轮是按需添加的，
    * 只要在当前时间轮容纳不了给定的延时任务时，才会触发将该延时任务提交给上层时间轮管理，
    * 此时如果上层时间轮还未定义，则会调用该方法初始化上层时间轮
    *
    *
    * 这里需要注意的地方就是对应上层时间轮的字段赋值，由方法实现可以看出
    * 上层时间轮中每一个时间格的时间跨度 tickMs 等于当前时间轮的总时间跨度 interval，
    * 而时间格格数仍保持不变，对应的任务计数器 taskCounter 和任务队列 queue 都是全局共用的。
    */
  private[this] def addOverflowWheel(): Unit = {
    synchronized {
      if (overflowWheel == null) {
        /**
          * 创建上层时间轮
          */
        overflowWheel = new TimingWheel(
          tickMs = interval, // tickMs 是当前时间轮的时间跨度 interval
          wheelSize = wheelSize, // 时间轮的格数不变
          startMs = currentTime, // 创建时间即当前时间
          taskCounter = taskCounter, // 全局唯一的任务计数器
          queue // 全局唯一的任务队列
        )
      }
    }
  }

  /**
    * 用于往时间轮中添加延时任务，该方法接收一个 TimerTaskEntry 类型对象，
    * 即对延时任务 TimerTask 的封装
    * @param timerTaskEntry
    * @return
    */
  def add(timerTaskEntry: TimerTaskEntry): Boolean = {
    /**
      * 获取任务的到期时间戳
      */
    val expiration = timerTaskEntry.expirationMs

    if (timerTaskEntry.cancelled) {
      // Cancelled
      /**
        * 任务已经被取消，则不应该被添加
        */
      false
    } else if (expiration < currentTime + tickMs) {
      // Already expired
      /**
        * 任务已经到期，则不应该被添加
        */
      false
    } else if (expiration < currentTime + interval) {
      // Put in its own bucket
      /**
        * 任务正好位于当前时间轮的时间跨度范围内，
        * 依据任务的到期时间查找此任务所属的时间格，并将任务添加到对应的时间格中
        */
      val virtualId = expiration / tickMs
      val bucket = buckets((virtualId % wheelSize.toLong).toInt)
      bucket.add(timerTaskEntry)

      // Set the bucket expiration time
      /**
        * 更新对应时间格的时间区间上界，如果是第一次往对应时间格中添加延时任务，则需要将时间格记录到全局任务队列中
        */
      if (bucket.setExpiration(virtualId * tickMs)) {
        // The bucket needs to be enqueued because it was an expired bucket
        // We only need to enqueue the bucket when its expiration time has changed, i.e. the wheel has advanced
        // and the previous buckets gets reused; further calls to set the expiration within the same wheel cycle
        // will pass in the same value and hence return false, thus the bucket with the same expiration will not
        // be enqueued multiple times.
        queue.offer(bucket)
      }
      true
    } else {
      /**
        * 已经超出了当前时间轮的时间跨度范围，将任务添加到上层时间轮中
        */
      // Out of the interval. Put it into the parent timer
      if (overflowWheel == null) addOverflowWheel()
      overflowWheel.add(timerTaskEntry)
    }
  }

  // Try to advance the clock
  /**
    * 推进当前时间轮指针
    * @param timeMs
    */
  def advanceClock(timeMs: Long): Unit = {
    if (timeMs >= currentTime + tickMs) {
      /**
        * 尝试推动指针，可能会往前推进多个时间格
        */
      currentTime = timeMs - (timeMs % tickMs)

      // Try to advance the clock of the overflow wheel if present
      /**
        * 尝试推动上层时间轮指针
        */
      if (overflowWheel != null) overflowWheel.advanceClock(currentTime)
    }
  }
}
