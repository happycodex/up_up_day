

## Kafka Producer 内存机制



## 一 、 Producer核心流程概览

![Producer流程分析](D:%5Ckaikeba%5Ckafka%5Ckafka-day02%5Ckafka%E7%AC%AC%E4%BA%8C%E6%AC%A1%E8%AF%BE.assets%5CProducer%E6%B5%81%E7%A8%8B%E5%88%86%E6%9E%90.png)

- 1、ProducerInterceptors是一个拦截器，对发送的数据进行拦截

  ```
  ps：说实话这个功能其实没啥用，我们即使真的要过滤，拦截一些消息，一般不考虑使用它，我们直接发送数据之前自己可以用代码过滤
  ```

- 2、Serializer 对消息的key和value进行序列化

- 3、通过使用分区器作用在每一条消息上，实现数据分发进行入到topic不同的分区中

- 4、RecordAccumulator收集消息，实现批量发送

  ```
  它是一个缓冲区，可以缓存一批数据，把topic的每一个分区数据存在一个队列中，然后封装消息成一个一个的batch批次，最后实现数据分批次批量发送。
  ```

- 5、Sender线程从RecordAccumulator获取消息

- 6、构建ClientRequest对象

- 7、将ClientRequest交给 NetWorkClient准备发送

- 8、NetWorkClient 将请求放入到KafkaChannel的缓存

- 9、发送请求到kafka集群

- 10、调用回调函数，接受到响应



## 二、 RecordAccumulator内存分配机制

### 1、RecordAccumulator中核心参数

- ==retries==

  - 重新发送数据的次数


  - 默认为0，表示不重试

  ==retry.backoff.ms==

  - 两次重试之间的时间间隔

  - 默认为100ms

- ==buffer.memory==

  - 设置发送消息的缓冲区，默认值是33554432，就是32MB

  ```
  如果发送消息出去的速度小于写入消息进去的速度，就会导致缓冲区写满，此时生产消息就会阻塞住，所以说这里就应该多做一些压测，尽可能保证说这块缓冲区不会被写满导致生产行为被阻塞住
  ```

- ==compression.type==

  - producer用于压缩数据的压缩类型。默认是none表示无压缩。可以指定gzip、snappy
  - 压缩最好用于批量处理，批量处理消息越多，压缩性能越好。

- ==batch.size==

  - producer将试图批处理消息记录，以减少请求次数。这将改善client与server之间的性能。
  - 默认是16384Bytes，即16kB，也就是一个batch满了16kB就发送出去

  ```
  如果batch太小，会导致频繁网络请求，吞吐量下降；如果batch太大，会导致一条消息需要等待很久才能被发送出去，而且会让内存缓冲区有很大压力，过多数据缓冲在内存里。
  ```

- ==linger.ms==

  - 这个值默认是0，就是消息必须立即被发送

  ```
  	一般设置一个100毫秒之类的，这样的话就是说，这个消息被发送出去后进入一个batch，如果100毫秒内，这个batch满了16kB，自然就会发送出去。
  	但是如果100毫秒内，batch没满，那么也必须把消息发送出去了，不能让消息的发送延迟时间太长，也避免给内存造成过大的一个压力。
  ```

- ==max.request.size==	

  - 这个参数用来控制发送出去的消息的大小，默认是1048576字节，也就1mb

  - 这个一般太小了，很多消息可能都会超过1mb的大小，建议设置更大一些（企业一般设置成10M）

    

### 2、初始化构造

![image-20201026103314813](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026103314813.png)

```java
// 在KafkaProducer中初始化
this.accumulator = new RecordAccumulator(config.getInt(ProducerConfig.BATCH_SIZE_CONFIG),
        this.totalMemorySize,
        this.compressionType,
        config.getLong(ProducerConfig.LINGER_MS_CONFIG),
        retryBackoffMs,
        metrics,
        time);
```

```java
//  RecordAccumulator的构造器    
public RecordAccumulator(int batchSize,
                             long totalSize,
                             CompressionType compression,
                             long lingerMs,
                             long retryBackoffMs,
                             Metrics metrics,
                             Time time) {
        this.drainIndex = 0;
        this.closed = false;
        this.flushesInProgress = new AtomicInteger(0);
        this.appendsInProgress = new AtomicInteger(0);
        this.batchSize = batchSize;
        this.compression = compression;
        this.lingerMs = lingerMs;
        this.retryBackoffMs = retryBackoffMs;
        this.batches = new CopyOnWriteMap<>();
        String metricGrpName = "producer-metrics";
        this.free = new BufferPool(totalSize, batchSize, metrics, time, metricGrpName);
        this.incomplete = new IncompleteRecordBatches();
        this.muted = new HashSet<>();
        this.time = time;
        registerMetrics(metrics, metricGrpName);
    }
```

```java
    // BufferPool构造器
    public BufferPool(long memory, int poolableSize, Metrics metrics, Time time, String metricGrpName) {
        this.poolableSize = poolableSize;
        this.lock = new ReentrantLock();
        this.free = new ArrayDeque<ByteBuffer>();
        this.waiters = new ArrayDeque<Condition>();
        this.totalMemory = memory;
        this.availableMemory = memory;
        this.metrics = metrics;
        this.time = time;
        this.waitTime = this.metrics.sensor("bufferpool-wait-time");
        MetricName metricName = metrics.metricName("bufferpool-wait-ratio",
                                                   metricGrpName,
                                                   "The fraction of time an appender waits for space allocation.");
        this.waitTime.add(metricName, new Rate(TimeUnit.NANOSECONDS));
    }
```

### 3、RecordAccumulator封装消息

==计算一个批次的大小--->根据批次大小分配内存--->再次尝试把数据写入到批次中---->根据内存大小封装批次==

```java
public RecordAppendResult append(TopicPartition tp,
                                 long timestamp,
                                 byte[] key,
                                 byte[] value,
                                 Callback callback,
                                 long maxTimeToBlock) throws InterruptedException {
    // We keep track of the number of appending thread to make sure we do not miss batches in
    // abortIncompleteBatches().
    appendsInProgress.incrementAndGet();
    try {
        // check if we have an in-progress batch
        Deque<RecordBatch> dq = getOrCreateDeque(tp);
        // 内存分配 上 分段加锁
        synchronized (dq) {
            if (closed)
                throw new IllegalStateException("Cannot send after the producer is closed.");
            RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
            if (appendResult != null)
                return appendResult;
        }

        // we don't have an in-progress record batch try to allocate a new batch
        int size = Math.max(this.batchSize, Records.LOG_OVERHEAD + Record.recordSize(key, value));
        log.trace("Allocating a new {} byte message buffer for topic {} partition {}", size, tp.topic(), tp.partition());
        // 申请 buffer
        ByteBuffer buffer = free.allocate(size, maxTimeToBlock);
        synchronized (dq) {
            // Need to check if producer is closed again after grabbing the dequeue lock.
            if (closed)
                throw new IllegalStateException("Cannot send after the producer is closed.");
            
            RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
            if (appendResult != null) {
                // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
                // 归还 buffer
                free.deallocate(buffer);
                return appendResult;
            }
             // 使用 buffer
            MemoryRecords records = MemoryRecords.emptyRecords(buffer, compression, this.batchSize);
            RecordBatch batch = new RecordBatch(tp, records, time.milliseconds());
            FutureRecordMetadata future = Utils.notNull(batch.tryAppend(timestamp, key, value, callback, time.milliseconds()));

            dq.addLast(batch);
            incomplete.add(batch);
            return new RecordAppendResult(future, dq.size() > 1 || batch.records.isFull(), true);
        }
    } finally {
        appendsInProgress.decrementAndGet();
    }
}
```



### 4、申请ByteBuffer

![image-20201026110615717](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026110615717.png)

```java
public ByteBuffer allocate(int size, long maxTimeToBlockMs) throws InterruptedException {
    if (size > this.totalMemory)
        throw new IllegalArgumentException("Attempt to allocate " + size
                                           + " bytes, but there is a hard limit of "
                                           + this.totalMemory
                                           + " on memory allocations.");

    this.lock.lock();
    try {
        // check if we have a free buffer of the right size pooled
        if (size == poolableSize && !this.free.isEmpty())
            return this.free.pollFirst();

        // now check if the request is immediately satisfiable with the
        // memory on hand or if we need to block
        int freeListSize = this.free.size() * this.poolableSize;
        if (this.availableMemory + freeListSize >= size) {
            // we have enough unallocated or pooled memory to immediately
            // satisfy the request
            freeUp(size);
            this.availableMemory -= size;
            lock.unlock();
            return ByteBuffer.allocate(size);
        } else {
            // we are out of memory and will have to block
            int accumulated = 0;
            ByteBuffer buffer = null;
            Condition moreMemory = this.lock.newCondition();
            long remainingTimeToBlockNs = TimeUnit.MILLISECONDS.toNanos(maxTimeToBlockMs);
            this.waiters.addLast(moreMemory);
            // loop over and over until we have a buffer or have reserved
            // enough memory to allocate one
            while (accumulated < size) {
                long startWaitNs = time.nanoseconds();
                long timeNs;
                boolean waitingTimeElapsed;
                try {
                    waitingTimeElapsed = !moreMemory.await(remainingTimeToBlockNs, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    this.waiters.remove(moreMemory);
                    throw e;
                } finally {
                    long endWaitNs = time.nanoseconds();
                    timeNs = Math.max(0L, endWaitNs - startWaitNs);
                    this.waitTime.record(timeNs, time.milliseconds());
                }

                if (waitingTimeElapsed) {
                    this.waiters.remove(moreMemory);
                    throw new TimeoutException("Failed to allocate memory within the configured max blocking time " + maxTimeToBlockMs + " ms.");
                }

                remainingTimeToBlockNs -= timeNs;
                // check if we can satisfy this request from the free list,
                // otherwise allocate memory
                if (accumulated == 0 && size == this.poolableSize && !this.free.isEmpty()) {
                    // just grab a buffer from the free list
                    buffer = this.free.pollFirst();
                    accumulated = size;
                } else {
                    // we'll need to allocate memory, but we may only get
                    // part of what we need on this iteration
                    freeUp(size - accumulated);
                    int got = (int) Math.min(size - accumulated, this.availableMemory);
                    this.availableMemory -= got;
                    accumulated += got;
                }
            }

            // remove the condition for this thread to let the next thread
            // in line start getting memory
            Condition removed = this.waiters.removeFirst();
            if (removed != moreMemory)
                throw new IllegalStateException("Wrong condition: this shouldn't happen.");

            // signal any additional waiters if there is more memory left
            // over for them
            if (this.availableMemory > 0 || !this.free.isEmpty()) {
                if (!this.waiters.isEmpty())
                    this.waiters.peekFirst().signal();
            }

            // unlock and return the buffer
            lock.unlock();
            if (buffer == null)
                return ByteBuffer.allocate(size);
            else
                return buffer;
        }
    } finally {
        if (lock.isHeldByCurrentThread())
            lock.unlock();
    }
}
```



### 5、使用ByteBuffer

![image-20201026135849213](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026135849213.png)

```java
private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, Deque<RecordBatch> deque) {
    //todo: 获取到队列中最后一个批次
    RecordBatch last = deque.peekLast();
    //todo: 第一次进来是没有批次的，if不会执行，直接返回null
    if (last != null) {
        FutureRecordMetadata future = last.tryAppend(timestamp, key, value, callback, time.milliseconds());
        if (future == null)
            last.records.close();
        else
            return new RecordAppendResult(future, deque.size() > 1 || last.records.isFull(), false);
    }
    //todo: 第一次直接返回null
    return null;
}
```



```java
// RecordBatch
public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, long now) {
    if (!this.records.hasRoomFor(key, value)) {
        return null;
    } else {
        long checksum = this.records.append(offsetCounter++, timestamp, key, value);
        this.maxRecordSize = Math.max(this.maxRecordSize, Record.recordSize(key, value));
        this.lastAppendTime = now;
        FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                               timestamp, checksum,
                                                               key == null ? -1 : key.length,
                                                               value == null ? -1 : value.length);
        if (callback != null)
            thunks.add(new Thunk(callback, future));
        this.recordCount++;
        return future;
    }
}
```

### 6、归还ByteBuffer

![image-20201026110639095](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026110639095.png)

```java
public void deallocate(ByteBuffer buffer, int size) {
    lock.lock();
    try {
        if (size == this.poolableSize && size == buffer.capacity()) {
            buffer.clear();
            this.free.add(buffer);
        } else {
            this.availableMemory += size;
        }
        Condition moreMem = this.waiters.peekFirst();
        if (moreMem != null)
            moreMem.signal();
    } finally {
        lock.unlock();
    }
}

public void deallocate(ByteBuffer buffer) {
    deallocate(buffer, buffer.capacity());
}
```

### 7、大于16kb消息处理

![image-20201026111311055](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026111311055.png)

如果availableMemory不够20kb时，就从Deque的尾部一个一个的释放16kb的ByteBuffer，直至够封装该条消息。

该部分的内存使用完直接归还给availableMemory

```java
private void freeUp(int size) {
    while (!this.free.isEmpty() && this.availableMemory < size)
        this.availableMemory += this.free.pollLast().capacity();
}
```

### 8、内存结构

![](Kafka%20Producer%20%E5%86%85%E5%AD%98%E6%9C%BA%E5%88%B6/image-20201026011638225.png)

Kafka Producer内存设计是非常巧妙的，给批次分配对应大小的内存，这是用到了内存池的设计，通过内存池的使用，可以实现内存的复用，不需要进行频繁的GC。

