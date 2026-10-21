package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.AddressBookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressBookServiceImpl implements AddressBookService {
    @Autowired
    private AddressBookMapper addressBookMapper;

    @Override
    public List<AddressBook> list(AddressBook addressBook) {
        addressBook.setUserId(currentUser());
        return addressBookMapper.list(addressBook);
    }

    @Override
    public void save(AddressBook addressBook) {
        validate(addressBook, true);
        addressBook.setId(null);
        addressBook.setUserId(currentUser());
        addressBook.setIsDefault(0);
        addressBookMapper.insert(addressBook);
    }

    @Override
    public AddressBook getById(Long id) {
        requireId(id);
        AddressBook row = addressBookMapper.getById(id, currentUser());
        if (row == null) throw new AddressBookBusinessException("地址不存在或无权访问");
        return row;
    }

    @Override
    public void update(AddressBook addressBook) {
        validate(addressBook, false);
        getById(addressBook.getId());
        addressBook.setUserId(currentUser());
        // 默认状态只允许通过专用接口切换，普通编辑不接受客户端的 isDefault。
        addressBook.setIsDefault(null);
        addressBookMapper.update(addressBook);
    }

    @Override
    @Transactional
    public void setDefault(AddressBook addressBook) {
        requireId(addressBook.getId());
        Long userId = currentUser();
        if (addressBookMapper.lockUser(userId) == null) {
            throw new AddressBookBusinessException("用户不存在");
        }
        getById(addressBook.getId());
        addressBookMapper.clearDefault(userId);
        if (addressBookMapper.setDefault(addressBook.getId(), userId) != 1) {
            throw new AddressBookBusinessException("设置默认地址失败");
        }
    }

    @Override
    public void deleteById(Long id) {
        getById(id);
        addressBookMapper.deleteById(id, currentUser());
    }

    private Long currentUser() {
        Long id = BaseContext.getCurrentId();
        if (id == null || id <= 0) throw new AddressBookBusinessException("用户未登录");
        return id;
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) throw new AddressBookBusinessException("地址 ID 必须大于 0");
    }

    private void validate(AddressBook address, boolean create) {
        checkText(address.getConsignee(), 50, create, "收货人");
        checkText(address.getPhone(), 11, create, "手机号");
        checkText(address.getDetail(), 200, create, "详细地址");
        if (address.getPhone() != null && !address.getPhone().matches("1[3-9][0-9]{9}")) {
            throw new AddressBookBusinessException("手机号格式不正确");
        }
        if (address.getSex() != null && !"0".equals(address.getSex()) && !"1".equals(address.getSex())) {
            throw new AddressBookBusinessException("性别只能是 0 或 1");
        }
        checkText(address.getProvinceCode(), 12, false, "省份编码");
        checkText(address.getProvinceName(), 32, false, "省份名称");
        checkText(address.getCityCode(), 12, false, "城市编码");
        checkText(address.getCityName(), 32, false, "城市名称");
        checkText(address.getDistrictCode(), 12, false, "区县编码");
        checkText(address.getDistrictName(), 32, false, "区县名称");
        checkText(address.getLabel(), 100, false, "标签");
    }

    private void checkText(String text, int max, boolean required, String field) {
        if ((required && text == null) || (text != null && (text.isBlank() || text.length() > max))) {
            throw new AddressBookBusinessException(field + "不能为空且不能超过 " + max + " 字符");
        }
    }
}
