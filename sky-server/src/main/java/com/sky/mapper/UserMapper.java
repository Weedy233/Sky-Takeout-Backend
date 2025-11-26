package com.sky.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.User;

@Mapper
public interface UserMapper {

    @Select("select * from user where openid = #{openid}")
    public User getByOpenid(String openid);


    public void insert(User user);

    @Select("select * from user where id = #{userId}")
    public User getById(Long userId);

    public Long getCountMap(Map<String,Object> map);
}
