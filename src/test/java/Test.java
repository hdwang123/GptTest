import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subscribers.DisposableSubscriber;

import java.util.concurrent.TimeUnit;

/**
 * @author wanghuidong
 * 时间： 2023/6/15 14:37
 */
public class Test {

    public static void main(String[] args) throws InterruptedException {
        //构建两个元素的异步流
        Disposable d = Flowable.just("Hello world!", "Thank you!")
                //每次发射元素前的延时
                .delay(1, TimeUnit.SECONDS)
                .subscribeWith(new DisposableSubscriber<String>() {
                    @Override
                    public void onStart() {
                        System.out.println("Start!");
                        //刚注册上请求1个元素
                        request(1);
                    }

                    @Override
                    public void onNext(String t) {
                        System.out.println(t);
                        //继续请求下一个元素
                        request(1);
                    }

                    @Override
                    public void onError(Throwable t) {
                        t.printStackTrace();
                    }

                    @Override
                    public void onComplete() {
                        System.out.println("Done!");
                    }
                });

        Thread.sleep(5000);
        // the sequence can now be cancelled via dispose()
        d.dispose();
    }
}
