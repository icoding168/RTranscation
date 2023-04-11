package com.rt;

import com.rt.db.DBPool;
import com.rt.db.MyJDBCDBPool;
import com.rt.rm.RMUtil;
import com.rt.rm.XAResourceManager;
import com.rt.tm.TransactionManager;
import com.rt.tm.TransactionManagerImpl;
import javax.transaction.xa.XAException;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

/**
 * @author hejianglong
 * @date 2019/10/12
 */
public class App {

    private static final String URL = "jdbc:mysql://localhost:3306/test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
    private static final String URL2 = "jdbc:mysql://localhost:3307/test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";

    // 全局事务管理器只有一个
    private static final TransactionManager transactionManager = TransactionManagerImpl.getSingleton();

    private static final DBPool dbPool1 = new MyJDBCDBPool("root", "123456", URL);

    private static final DBPool dbPool2 = new MyJDBCDBPool("root", "123456", URL2);


    public static void main(String[] args) {
        concurrentTest(100);
    }

    /**
     * 并发测试
     * 同时开启多个全局事务
     * 每个全局事务对应两个分支事务
     *
     * 暂时未做远程事务支持
     * @param threadNum
     */
    private static void concurrentTest(int threadNum) {
        final CountDownLatch countDownLatch = new CountDownLatch(threadNum);

        for (int i = 0; i < threadNum; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        countDownLatch.await();
                        // 开启全局事务
                        transactionManager.begin();
                        // 向服务器 A 数据库写入数据
                        saveDB1();
                        // 向服务器 B 数据库写入数据
                        saveDB2();
                        // 询问 RM 分支事务是否准备就绪
                        boolean prepareSuccess = transactionManager.prepare();
                        // 目前没有涉及到远程事务的支持，在本地都是同步的方式调用所以此处没有做做阻塞等待而是返回立刻知道是否成功
                        // 如果涉及到远程事务的支持，那么此处应该就有一个阻塞唤醒机制
                        if (prepareSuccess) {
                            // 开始提交分支事务
                            transactionManager.commit();
                        } else {
                            // 回滚
                            transactionManager.rollback();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        // 如果出错了就进行回滚各分支事务
                        try {
                            transactionManager.rollback();
                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }
                    } finally {
                        // 资源回收
                        GlobalInfo.remove();
                    }
                }
            }).start();
            countDownLatch.countDown();
        }
    }

    private static void saveDB1() throws XAException, SQLException {
        // 因为存在多个数据源，所以需要指定是使用哪一个数据源
        XAResourceManager xaResourceManager = RMUtil.getResourceManager(dbPool1);
        // 分支事务开启
        xaResourceManager.begin();
        xaResourceManager.execute("insert into test1(name, age)  values('pt', 21)");
        // 事务执行完毕处于准备阶段等待 TM 下达 commit 指令
        xaResourceManager.prepare();
    }

    private static void saveDB2() throws XAException, SQLException {
        XAResourceManager xaResourceManager = RMUtil.getResourceManager(dbPool2);
        // 分支事务开启
        xaResourceManager.begin();
        xaResourceManager.execute("insert into test2(name, age) values('tom', 22)");
        // 事务执行完毕处于准备阶段等待 TM 下达 commit 指令
        xaResourceManager.prepare();
        // 测试回滚
        // throw new RuntimeException("xx");
    }


}
