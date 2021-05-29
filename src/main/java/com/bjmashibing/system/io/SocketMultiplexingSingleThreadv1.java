package com.bjmashibing.system.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class SocketMultiplexingSingleThreadv1 {

    private ServerSocketChannel server = null;
    /**
     * linux 多路复用器（select poll / epoll kqueue） nginx event {}
     */
    private Selector selector = null;
    int port = 9090;

    public void initServer() {
        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));
            /**
             * 如果在epoll 模型下，open --> epoll_create -> fd3
             */
            selector = Selector.open();  //  select  poll  *epoll
            /**
             * server 约等于 listen 状态的 fd4
             * register
             * 如果：
             * select ， poll ：jvm里开辟一个数组 fd4 放进去
             * epoll ： epoll_ctl (fd3, ADD, fd4,EPOLLIN)
             */
            server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        initServer();
        System.out.println("服务器启动了。。。。。");
        try {
            while (true) {
                Set<SelectionKey> keys = selector.keys();
                System.out.println(keys.size()+"   size");
                /**
                 * 1.调用多路复用器（select ， poll or epoll （epoll_wait))
                 * select 是啥意思：
                 * 1.select , poll 其实 内核的select（fd4） poll（fd4）
                 * 2.epoll : 其实内核的epoll_wait()
                 * 3.参数可以带时间：没有时间 0 阻塞，有时间设置一个超时
                 * 4.selector.wakeup()返回结果0
                 */
                while (selector.select(500) > 0) {
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectionKeys.iterator();
                    /**
                     * 因此，不管你啥多路复用器，你只能给我状态，我还要一个一个的去处理他们的R/W。同步好辛苦！
                     * NIO 自己对着每一个fd调用系统，浪费资源，那么你看，这里不是调用了一次select方法，知道具体哪些可以R/W了。
                     * 我们前边可以强调过，socket ： listen 通信 R/W
                     */
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isAcceptable()) {
                            /**
                             * 看代码的时候这里是重点，如果要去接收一个新的连接
                             * 语义上，accept接受连接且返回新的连接的FD对吧？
                             * 那新的FD怎么办？
                             * select，poll ：因为他们内核没有空间，那么在jvm中保存和前边的fd4那个listen的一起
                             * epoll： 我们希望通过epoll_ctl把新的客户端fd注册到内核空间
                             */
                            acceptHandler(key);
                        } else if (key.isReadable()) {
                            /**在当前线程，这个方法可能会阻塞,如果阻塞了十年，其他的IO早就没电了 **/
                            /** 所以，为什么提出了IO THREADS **/
                            /** redis 是不是用了epoll，redis是不是有个 io threads 的概念， redis 是不是单线程的**/
                            /**tomcat 8，9 异步处理的方式IO 和 处理上解耦**/
                            readHandler(key);//
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void acceptHandler(SelectionKey key) {
        try {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel client = ssc.accept();
            client.configureBlocking(false);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            /**
             * select,poll : jvm 里开辟了一个数组 fd7 放进去
             * epoll: epoll_ctl(fd3,ADD,fd7,EPOLLIN)
             */
            client.register(selector, SelectionKey.OP_READ, buffer);
            System.out.println("-------------------------------------------");
            System.out.println("新客户端：" + client.getRemoteAddress());
            System.out.println("-------------------------------------------");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void readHandler(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        buffer.clear();
        int read = 0;
        try {
            while (true) {
                read = client.read(buffer);
                if (read > 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        client.write(buffer);
                    }
                    buffer.clear();
                } else if (read == 0) {
                    break;
                } else {
                    client.close();
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SocketMultiplexingSingleThreadv1 service = new SocketMultiplexingSingleThreadv1();
        service.start();
    }
}
