package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果失败返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //将验证码保存在redis里
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code);
        stringRedisTemplate.expire(LOGIN_CODE_KEY + phone,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //发送验证码
        log.debug("手机验证码为: {}",code);
        //返回
        return Result.ok();
    }

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            //如果失败返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //校验验证码
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        String code = loginForm.getCode();
        if(cacheCode == null || !String.valueOf(cacheCode).equals(code)){
            //若校验未通过则返回异常
            return Result.fail("验证码错误!");
        }
        //若校验通过
        //检查是否是已注册用户，不是则将新用户添加进数据库
        User user = query().eq("phone",loginForm.getPhone()).one();
        if(user == null){
            user = createUserByPhone(loginForm.getPhone());
        }
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        //将用户保存在redis中
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId().toString());
        map.put("nickName", user.getNickName());
        map.put("icon", user.getIcon() == null ? "" : user.getIcon());
        String token = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,map);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //通过放行
        return Result.ok(token);
    }

    /**
     * 添加新用户
     * @param phone
     * @return
     */
    public User createUserByPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return user;
    }

}
