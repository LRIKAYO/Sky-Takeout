package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.PasswordEditDTO;
import com.sky.context.BaseContext;
import com.sky.exception.PasswordEditFailedException;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.result.PageResult;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
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

        // 对提交的密码进行 MD5 摘要处理后比对
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

    @Override
    public void save(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();

        //将前端信息复制到员工实体
        BeanUtils.copyProperties(employeeDTO, employee);

        //设置默认密码
        employee.setPassword(
            DigestUtils.md5DigestAsHex(
                PasswordConstant.DEFAULT_PASSWORD.getBytes()
            )
        );

        //新员工默认启用
        employee.setStatus(StatusConstant.ENABLE);

        employeeMapper.insert(employee);

    }

    @Override
    public void editPassword(PasswordEditDTO dto) {
        Long employeeId = BaseContext.getCurrentId();
        if (employeeId == null || employeeId <= 0 || dto == null
                || (dto.getEmpId() != null && !employeeId.equals(dto.getEmpId()))) {
            throw new PasswordEditFailedException("只能修改当前登录员工的密码");
        }
        // 原管理端页面只提交新旧密码；缺省 empId 时仍以 JWT 中的本人身份执行。
        if (dto.getOldPassword() == null || dto.getOldPassword().isBlank()
                || dto.getNewPassword() == null || !dto.getNewPassword().matches("[A-Za-z0-9]{6,20}")) {
            throw new PasswordEditFailedException("原密码不能为空，新密码须为6至20位数字或字母");
        }
        String oldHash = DigestUtils.md5DigestAsHex(dto.getOldPassword().getBytes(StandardCharsets.UTF_8));
        String newHash = DigestUtils.md5DigestAsHex(dto.getNewPassword().getBytes(StandardCharsets.UTF_8));
        if (oldHash.equals(newHash)) {
            throw new PasswordEditFailedException("新密码不能与原密码相同");
        }
        // 旧密码和写入在同一条 SQL 校验，两个并发改密请求不能都通过旧密码检查。
        if (employeeMapper.changePassword(employeeId, oldHash, newHash, LocalDateTime.now()) != 1) {
            throw new PasswordEditFailedException("原密码错误或账号不可用");
        }
    }

    @Override
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        //开始分页查询
        PageHelper.startPage(
            employeePageQueryDTO.getPage(),
            employeePageQueryDTO.getPageSize()
        );

        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);

        long total = page.getTotal();
        List<Employee> records = page.getResult();

        return new PageResult(total, records);
    }

    @Override
    public void startOrStop(Integer status, Long id) {
        Employee employee = Employee.builder()
                            .status(status)
                            .id(id)
                            .build();

        employeeMapper.update(employee);
    }

    @Override
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);
        employee.setPassword("****");
        return employee;
    }

    @Override
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);

        employeeMapper.update(employee);

    }
}
