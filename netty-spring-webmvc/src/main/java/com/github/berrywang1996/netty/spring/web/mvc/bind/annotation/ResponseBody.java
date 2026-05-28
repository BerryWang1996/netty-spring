/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation indicating that the return value of a handler method should be
 * serialized directly into the HTTP response body (typically as JSON) rather
 * than being interpreted as an HTML view name.
 * <p>
 * Can be applied at the method level to affect a single endpoint, or at the
 * class level to apply to all methods in the controller. When present, the
 * framework selects {@link com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler JsonViewHandler}
 * for rendering the response.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseBody {
}
