package com.witos.ems.server.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmsUserProfileMapperSqlTest
{
    @Test
    void firstCompanyBindingUsesOnlyColumnsPresentInUserProfile() throws Exception
    {
        Method method = EmsUserProfileMapper.class.getMethod(
                "bindFirstCompanyToDefaultAdmin", Long.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).toLowerCase();

        assertTrue(sql.contains("p.status = '0'"));
        assertFalse(sql.contains("p.del_flag"));
        assertTrue(sql.contains("c.del_flag = '0'"));
    }
}
