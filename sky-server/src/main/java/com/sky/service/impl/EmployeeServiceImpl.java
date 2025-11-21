package com.sky.service.impl;

import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.result.PageResult;
import com.sky.service.EmployeeService;

/**
 * 员工业务默认实现，负责登录鉴权、资料维护、分页查询以及状态切换等操作。
 */

@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 校验用户名与密码并返回合法员工。
     *
     * @param employeeLoginDTO 登录请求参数
     * @return 认证通过的员工实体
     * @throws AccountNotFoundException 账号不存在
     * @throws PasswordErrorException   密码错误
     * @throws AccountLockedException   账号被禁用
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //密码比对
        password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!password.equals(employee.getPassword())) {
            //密码错误
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (employee.getStatus() == StatusConstant.DISABLE) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //3、返回实体对象
        return employee;
    }

    /**
     * 新增员工并设置默认状态启用、默认密码为系统常量。
     *
     * @param employeeDTO 待保存的员工信息
     */
    @Override
    public void save(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();

        // 属性拷贝
        BeanUtils.copyProperties(employeeDTO, employee);

        // 设置默认状态为启用
        employee.setStatus(StatusConstant.ENABLE);

        // 设置默认密码为 123456
        employee.setPassword(DigestUtils.md5DigestAsHex(PasswordConstant.
                                                        DEFAULT_PASSWORD.
                                                        getBytes()));

        employeeMapper.insert(employee);
    }

    /**
     * 根据查询条件分页查询员工列表。
     *
     * @param employeePageQueryDTO 查询与分页参数
     * @return 包含总记录数及当前页数据的分页结果
     */
    @Override
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        PageHelper.startPage(employeePageQueryDTO.getPage(), 
                             employeePageQueryDTO.getPageSize());
        
        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);

        long total = page.getTotal();
        List<Employee> records = page.getResult();

        return new PageResult(total, records);
    }
    
    /**
     * 根据主键更新员工的启用/禁用状态。
     *
     * @param status 目标状态
     * @param id     员工主键
     */
    @Override
    public void enableOrDisable(Integer status, Long id) {
        Employee employee = Employee.builder()
                                    .status(status)
                                    .id(id)
                                    .build();
        
        employeeMapper.update(employee);
    }

    /**
     * 按 id 查询员工，并对密码字段脱敏。
     *
     * @param id 员工主键
     * @return 员工实体，密码字段以占位符代替
     */
    @Override
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);
        employee.setPassword("****");
        return employee;
    }

    /**
     * 更新员工的基础资料（不含密码重置逻辑）。
     *
     * @param employeeDTO 最新的员工数据
     */
    @Override
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);
        
        employeeMapper.update(employee);
    }
}
