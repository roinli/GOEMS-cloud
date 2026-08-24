package com.witos.auth.form;

import lombok.Data;

/**
 * 用户注册对象
 *
 * @author witos
 */
@Data
public class RegisterBody extends LoginBody
{
	private String accountOpenType;

	private String companyName;

	private String phonenumber;

	private String email;

	private String emailCode;

	private String province;

	private String city;

}
