package com.krrz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.krrz.dto.LoginFormDTO;
import com.krrz.dto.Result;
import com.krrz.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
