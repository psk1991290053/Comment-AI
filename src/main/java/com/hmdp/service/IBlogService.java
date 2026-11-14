package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询笔记
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞博客
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询最早点赞过该博客的五名用户基本信息
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布笔记
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);
}
