package com.scb.monitoring;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 전용 : 읽기 전용 트랜잭션에서 INSERT / UPDATE / DELETE 를 실행하면 실패시킴.
 * <p>
 * 운영 MySQL 은 읽기 전용 연결에서 쓰기를 거부(Connection is read-only)하지만 테스트용 H2 는 무시하고,
 * 또 @MybatisTest 는 테스트 전체를 쓰기 트랜잭션으로 감싸므로 서비스의 readOnly 가 드러나지 않음.
 * 그래서 Spring 프록시를 거쳐 처음 들어간 @Transactional 서비스 메서드(실제 트랜잭션을 시작하는 쪽)의
 * readOnly 를 직접 기록하고, 그 안에서 MyBatis 쓰기가 실행되면 운영과 같은 오류로 실패시킵니다.
 * 각 @MybatisTest 에 @Import(ReadOnlyWriteGuard.class) 로 넣습니다.
 */
@TestConfiguration
public class ReadOnlyWriteGuard {

    /** 현재 스레드에서 진입한 @Transactional 메서드의 readOnly (첫 원소 = 가장 바깥) */
    static final ThreadLocal<Deque<Boolean>> SCOPE = ThreadLocal.withInitial(ArrayDeque::new);

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor readOnlyScopeAdvisor() {
        ComposablePointcut pc = new ComposablePointcut(new AnnotationMatchingPointcut(Transactional.class, true))
                .union(AnnotationMatchingPointcut.forMethodAnnotation(Transactional.class));
        MethodInterceptor advice = inv -> {
            Class<?> target = inv.getThis() != null ? AopUtils.getTargetClass(inv.getThis()) : inv.getMethod().getDeclaringClass();
            if (!target.getName().startsWith("com.scb.monitoring.")) return inv.proceed();
            Method m = AopUtils.getMostSpecificMethod(inv.getMethod(), target);
            Transactional tx = AnnotatedElementUtils.findMergedAnnotation(m, Transactional.class);
            if (tx == null) tx = AnnotatedElementUtils.findMergedAnnotation(target, Transactional.class);
            Deque<Boolean> d = SCOPE.get();
            d.addLast(tx != null && tx.readOnly());
            try {
                return inv.proceed();
            } finally {
                d.removeLast();
            }
        };
        DefaultPointcutAdvisor a = new DefaultPointcutAdvisor(pc, advice);
        a.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return a;
    }

    @Bean
    public Interceptor readOnlyWriteInterceptor() {
        return new Guard();
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    public static class Guard implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Deque<Boolean> d = SCOPE.get();
            // 바깥 메서드가 트랜잭션을 시작하고 안쪽 REQUIRED 메서드는 거기에 참여 → 가장 바깥의 readOnly 가 연결 상태
            if (!d.isEmpty() && d.peekFirst()) {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                throw new IllegalStateException("읽기 전용 트랜잭션에서 쓰기 실행: " + ms.getId()
                        + " (운영 MySQL 'Connection is read-only' 오류 - 서비스 메서드에 @Transactional 필요)");
            }
            return invocation.proceed();
        }

        @Override
        public void setProperties(Properties properties) { }
    }
}
