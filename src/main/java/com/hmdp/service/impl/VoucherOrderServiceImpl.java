package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }

        // 2.判断秒杀是否开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀还未开始");
        }
        // 3.判断秒杀是否已经结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }
        // 4.判断库存是否充足
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 获取锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取分布式锁
        boolean tryLock = lock.tryLock(10);
        if (!tryLock) {
            return Result.fail("一个人只允许下一单");
        }
        try {
            // 5.创建订单
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,seckillVoucher);
        } finally {
            // 释放锁
            lock.unlock();
        }


    }


    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result createVoucherOrder(Long voucherId,SeckillVoucher seckillVoucher) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        // intern()方法是将字符串放入常量池中，如果常量池中已经存在该字符串，则直接返回常量池中的字符串
        // 如果常量池中不存在该字符串，则将该字符串放入常量池中，然后返回该字符串的引用
        // 这样可以保证锁的是同一个对象
        VoucherOrder voucherOrder = this.getOne(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucherId));
        if (voucherOrder != null) {
            return Result.fail("您已经秒杀过了");
        }


        // 5.扣件库存
        seckillVoucher.setStock(seckillVoucher.getStock() - 1);
        boolean flag = iSeckillVoucherService.update(seckillVoucher, new LambdaQueryWrapper<SeckillVoucher>().gt(SeckillVoucher::getStock, 0));
        if (!flag) {
            return Result.fail("扣减库存失败");
        }

        // 6.创建订单
        VoucherOrder order = new VoucherOrder();

        // 生成订单id
        long id = redisIdWorker.nextId("order");
        order.setId(id);

        order.setUserId(userId);

        // 优惠券id
        order.setVoucherId(voucherId);

        this.save(order);

        // 7.返回订单id
        return Result.ok(order.getId());
    }
}
