package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    @Autowired
    private UserServiceImpl userService;

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
        //返回
        return Result.ok(blog);
    }
}
