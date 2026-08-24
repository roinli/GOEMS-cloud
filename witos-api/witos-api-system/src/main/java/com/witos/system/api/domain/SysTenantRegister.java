package com.witos.system.api.domain;

import lombok.Data;

import java.io.Serializable;

@Data
public class SysTenantRegister implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String companyName;

    private String username;

    private String password;

    private String phonenumber;

    private String email;

    private String province;

    private String city;

}