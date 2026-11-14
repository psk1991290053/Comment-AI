package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserServiceImpl userService;
    @Autowired
    private IFollowService followService;

    /**
     * 根据id查询笔记
     * @param id
     * @return
     */
    public Result queryBlogById(Long id) {
        //查询博客信息
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("查询的笔记不存在!");
        }
        //封装用户信息
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        String icon = user.getIcon();
        String nickName = user.getNickName();
        blog.setIcon(icon);
        blog.setName(nickName);
        //查询当前登录用户是否点赞过该条博客
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, UserHolder.getUser().getId().toString());
        blog.setIsLike(score != null);
        //返回
        return Result.ok(blog);
    }

    /**
     * 点赞博客
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查看当前用户是否点过赞
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score != null){
            //如果点过赞，则取消赞
            //先撤销set集合中的value
            Long isSuccess = stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            if(isSuccess == 1){
                //再update数据库 update blog set liked = liked - 1 where id = #{id};
                update().setSql("liked = liked - 1").eq("id",id).update();
            }
        }
        else{
            //如果没点过赞，则点赞
            //先把userId添加到set集合中
            Boolean isSuccess = stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            if(isSuccess == true){
                //再update数据库 update blog set liked = liked + 1 where id = #{id};
                update().setSql("liked = liked + 1").eq("id",id).update();
            }
        }
        return Result.ok();
    }

    /**
     * 查询最早点赞过该博客的五名用户的基本信息
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1.从sorted_set中取出前五名的value数据
        String key = "blog:liked:" + id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(range == null || range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.根据value查询用户信息，并封装为UserDTO
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回数据
        return Result.ok(userDTOs);
    }

    /**
     * 发布笔记
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("发布笔记失败!");
        }
        //3.推送博文至粉丝的收件箱
        //3.1获取粉丝列表 select * from tb_follow where follow_user_id = #{blog.getUserId()}
        List<Follow> fans = followService.query().eq("follow_user_id", blog.getUserId()).list();
        //3.2给每位粉丝的收件箱推送博文
        for (Follow fan : fans) {
            String key = "feed:" + fan.getUserId();
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //3.返回id
        return Result.ok(blog.getId());
    }
}
