package com.sky.mapper;

import com.sky.entity.AddressBook;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AddressBookMapper {
    List<AddressBook> list(AddressBook addressBook);

    @Insert("insert into address_book (user_id,consignee,phone,sex,province_code,province_name," +
            "city_code,city_name,district_code,district_name,detail,label,is_default) values " +
            "(#{userId},#{consignee},#{phone},#{sex},#{provinceCode},#{provinceName}," +
            "#{cityCode},#{cityName},#{districtCode},#{districtName},#{detail},#{label},#{isDefault})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AddressBook addressBook);

    @Select("select * from address_book where id=#{id} and user_id=#{userId}")
    AddressBook getById(@Param("id") Long id, @Param("userId") Long userId);

    int update(AddressBook addressBook);

    @Delete("delete from address_book where id=#{id} and user_id=#{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    // 同一用户的默认地址切换串行执行；锁随事务提交或回滚释放。
    @Select("select id from user where id=#{userId} for update")
    Long lockUser(Long userId);

    @Update("update address_book set is_default=0 where user_id=#{userId}")
    void clearDefault(Long userId);

    @Update("update address_book set is_default=1 where id=#{id} and user_id=#{userId}")
    int setDefault(@Param("id") Long id, @Param("userId") Long userId);
}
